// Local stand-in for API Gateway + CloudFormation table creation, for deny-egress rooms.
// It serves the candidate's public seam on :8080, routes by the SAM template, invokes the
// function container through the Lambda Runtime Interface Emulator, and creates the
// template's DynamoDB tables on dynamodb-local. Readiness is served on a separate port so
// it can never shadow a candidate route.
import http from "node:http";
import { readFile } from "node:fs/promises";
import { CreateTableCommand, DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { matchRoute, parseTemplate, routes, tableDefinitions, toEvent, toResponse } from "./sam-template.mjs";

const templatePath = process.env.SAM_TEMPLATE ?? "/sam/template.yaml";
const functionUrl = process.env.SAM_FUNCTION_URL ?? "http://function:8080/2015-03-31/functions/function/invocations";
const port = Number(process.env.PORT ?? 8080);
const readinessPort = Number(process.env.SAM_GATEWAY_READINESS_PORT ?? 8081);

const template = parseTemplate(await readFile(templatePath, "utf8"));
const routeTable = routes(template);
const tables = tableDefinitions(template);
let ready = false;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function createTables() {
  if (tables.length === 0) return;
  const client = new DynamoDBClient({});
  for (const table of tables) {
    for (let attempt = 1; ; attempt += 1) {
      try {
        await client.send(new CreateTableCommand(table));
        console.log(`created table ${table.TableName}`);
        break;
      } catch (error) {
        if (error?.name === "ResourceInUseException") break;
        if (attempt >= 120) throw error;
        await sleep(500);
      }
    }
  }
}

// The emulator serves one invocation at a time; serialize so concurrent clients queue, not fail.
let queue = Promise.resolve();
function invoke(event) {
  const run = queue.then(async () => {
    for (let attempt = 1; ; attempt += 1) {
      try {
        const response = await fetch(functionUrl, { method: "POST", body: JSON.stringify(event) });
        const text = await response.text();
        return text.length ? JSON.parse(text) : null;
      } catch (error) {
        if (attempt >= 40 || error?.cause?.code !== "ECONNREFUSED") throw error;
        await sleep(250);
      }
    }
  });
  queue = run.catch(() => {});
  return run;
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, "http://gateway");
    const match = matchRoute(routeTable, request.method, url.pathname);
    if (!match) {
      response.writeHead(404, { "content-type": "application/json" });
      response.end(JSON.stringify({ message: "Not Found" }));
      return;
    }
    const headers = Object.fromEntries(Object.entries(request.headers).map(([k, v]) => [k, Array.isArray(v) ? v.join(",") : v]));
    const event = toEvent(match, {
      method: request.method,
      path: url.pathname,
      query: url.search.slice(1),
      headers,
      body: await readBody(request),
    });
    const result = toResponse(match.route.payload, await invoke(event));
    response.writeHead(result.status, result.headers);
    response.end(result.body);
  } catch (error) {
    console.error(error);
    response.writeHead(502, { "content-type": "application/json" });
    response.end(JSON.stringify({ message: "Bad Gateway" }));
  }
});

http.createServer((_, response) => {
  response.writeHead(ready ? 200 : 503);
  response.end(ready ? "ready" : "starting");
}).listen(readinessPort);

await createTables();
server.listen(port, () => {
  ready = true;
  console.log(`sam gateway on :${port}, ${routeTable.length} route(s), ${tables.length} table(s)`);
});
