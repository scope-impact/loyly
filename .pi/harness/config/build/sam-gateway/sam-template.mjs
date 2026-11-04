// Pure SAM-template interpretation for the local gateway: routes, tables, and
// API Gateway event/response translation. No I/O lives here.
import { parse } from "yaml";

const scalarTags = ["Ref", "Sub", "GetAtt", "ImportValue", "Base64", "GetAZs", "Condition"];
const sequenceTags = [
  "Join", "Select", "Split", "If", "Equals", "Not", "And", "Or",
  "FindInMap", "Sub", "GetAtt", "Cidr",
];

const intrinsic = (name) => (name === "Ref" || name === "Condition" ? name : `Fn::${name}`);

const customTags = [
  ...scalarTags.map((name) => ({
    tag: `!${name}`,
    resolve: (value) => ({ [intrinsic(name)]: name === "GetAtt" ? value.split(".") : value }),
  })),
  ...sequenceTags.map((name) => ({
    tag: `!${name}`,
    collection: "seq",
    resolve: (seq) => ({ [intrinsic(name)]: seq.toJSON() }),
  })),
];

export function parseTemplate(text) {
  const template = parse(text, { customTags, logLevel: "error" });
  if (!template || typeof template !== "object" || typeof template.Resources !== "object") {
    throw new Error("SAM template must declare Resources");
  }
  return template;
}

const resources = (template, type) =>
  Object.entries(template.Resources).filter(([, resource]) => resource?.Type === type);

/** Local table name: a literal TableName, otherwise the logical ID (what `!Ref` resolves to locally). */
export function localTableName(logicalId, properties = {}) {
  return typeof properties.TableName === "string" ? properties.TableName : logicalId;
}

const simpleKeyType = { String: "S", Number: "N", Binary: "B" };

export function tableDefinitions(template) {
  const tables = resources(template, "AWS::DynamoDB::Table").map(([id, { Properties: p = {} }]) => {
    const provisioned = p.BillingMode === "PROVISIONED";
    return {
      TableName: localTableName(id, p),
      AttributeDefinitions: p.AttributeDefinitions,
      KeySchema: p.KeySchema,
      ...(p.GlobalSecondaryIndexes ? { GlobalSecondaryIndexes: p.GlobalSecondaryIndexes } : {}),
      ...(p.LocalSecondaryIndexes ? { LocalSecondaryIndexes: p.LocalSecondaryIndexes } : {}),
      BillingMode: provisioned ? "PROVISIONED" : "PAY_PER_REQUEST",
      ...(provisioned ? { ProvisionedThroughput: p.ProvisionedThroughput } : {}),
    };
  });
  const simple = resources(template, "AWS::Serverless::SimpleTable").map(([id, { Properties: p = {} }]) => {
    const key = p.PrimaryKey ?? { Name: "id", Type: "String" };
    return {
      TableName: localTableName(id, p),
      AttributeDefinitions: [{ AttributeName: key.Name, AttributeType: simpleKeyType[key.Type] ?? "S" }],
      KeySchema: [{ AttributeName: key.Name, KeyType: "HASH" }],
      BillingMode: "PAY_PER_REQUEST",
    };
  });
  for (const table of [...tables, ...simple]) {
    if (!Array.isArray(table.KeySchema) || !Array.isArray(table.AttributeDefinitions)) {
      throw new Error(`table ${table.TableName} must declare KeySchema and AttributeDefinitions`);
    }
  }
  return [...tables, ...simple];
}

function compilePath(path) {
  const names = [];
  const pattern = path.split("/").map((segment) => {
    const greedy = segment.match(/^\{(.+)\+\}$/);
    if (greedy) { names.push(greedy[1]); return "(.+)"; }
    const param = segment.match(/^\{(.+)\}$/);
    if (param) { names.push(param[1]); return "([^/]+)"; }
    return segment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }).join("/");
  return { regex: new RegExp(`^${pattern}/?$`), names };
}

/**
 * Phase-1 scope: exactly one function (a "lambdalith"); every HttpApi/Api event becomes a route.
 * An HttpApi event without Path/Method is the `$default` catch-all.
 */
export function routes(template) {
  const functions = resources(template, "AWS::Serverless::Function");
  if (functions.length !== 1) {
    throw new Error(`local gateway supports exactly one AWS::Serverless::Function, found ${functions.length}`);
  }
  const [functionId, fn] = functions[0];
  const result = [];
  for (const [eventId, event] of Object.entries(fn.Properties?.Events ?? {})) {
    if (event?.Type !== "HttpApi" && event?.Type !== "Api") continue;
    const p = event.Properties ?? {};
    const payload = event.Type === "HttpApi" ? "2.0" : "1.0";
    const method = String(p.Method ?? "ANY").toUpperCase().replace("X-AMAZON-APIGATEWAY-ANY-METHOD", "ANY");
    if (event.Type === "HttpApi" && p.Path === undefined) {
      result.push({ eventId, functionId, payload, method: "ANY", path: "$default", catchAll: true });
      continue;
    }
    result.push({ eventId, functionId, payload, method, path: p.Path, ...compilePath(p.Path) });
  }
  if (result.length === 0) throw new Error(`function ${functionId} declares no HttpApi or Api events`);
  // Literal paths before parameterized ones, catch-all last, as API Gateway prefers the most specific route.
  const specificity = (r) => (r.catchAll ? 2 : r.names.length > 0 ? 1 : 0);
  return result.sort((a, b) => specificity(a) - specificity(b));
}

export function matchRoute(table, method, path) {
  for (const route of table) {
    if (route.method !== "ANY" && route.method !== method) continue;
    if (route.catchAll) return { route, pathParameters: undefined };
    const match = route.regex.exec(path);
    if (match) {
      const pathParameters = Object.fromEntries(route.names.map((n, i) => [n, decodeURIComponent(match[i + 1])]));
      return { route, pathParameters: route.names.length ? pathParameters : undefined };
    }
  }
  return undefined;
}

const textual = /^(application\/(json|xml|x-www-form-urlencoded|javascript)|text\/)|\+json|\+xml/i;

export function toEvent({ route, pathParameters }, request) {
  const { method, path, query, headers, body } = request;
  const isBase64Encoded = body.length > 0 && !textual.test(headers["content-type"] ?? "");
  const encodedBody = body.length === 0 ? undefined : isBase64Encoded ? body.toString("base64") : body.toString("utf8");
  const queryStringParameters = query ? Object.fromEntries(new URLSearchParams(query)) : undefined;
  if (route.payload === "2.0") {
    const { cookie, ...rest } = headers;
    return {
      version: "2.0",
      routeKey: route.catchAll ? "$default" : `${route.method} ${route.path}`,
      rawPath: path,
      rawQueryString: query,
      ...(cookie ? { cookies: cookie.split(/;\s*/) } : {}),
      headers: rest,
      ...(queryStringParameters ? { queryStringParameters } : {}),
      ...(pathParameters ? { pathParameters } : {}),
      requestContext: {
        http: { method, path, protocol: "HTTP/1.1", sourceIp: "127.0.0.1", userAgent: headers["user-agent"] ?? "" },
        routeKey: route.catchAll ? "$default" : `${route.method} ${route.path}`,
        stage: "$default",
        timeEpoch: Date.now(),
      },
      ...(encodedBody !== undefined ? { body: encodedBody } : {}),
      isBase64Encoded,
    };
  }
  return {
    resource: route.path,
    path,
    httpMethod: method,
    headers,
    queryStringParameters: queryStringParameters ?? null,
    pathParameters: pathParameters ?? null,
    requestContext: { httpMethod: method, path, resourcePath: route.path, stage: "local" },
    body: encodedBody ?? null,
    isBase64Encoded,
  };
}

/** Map a Lambda result to an HTTP response, following API Gateway's payload-format rules. */
export function toResponse(payload, result) {
  if (result && typeof result === "object" && typeof result.errorMessage === "string" && result.statusCode === undefined) {
    return { status: 502, headers: { "content-type": "application/json" }, body: Buffer.from(JSON.stringify({ message: "Internal Server Error" })) };
  }
  const structured = result && typeof result === "object" && "statusCode" in result;
  if (!structured) {
    if (payload === "1.0") {
      return { status: 502, headers: { "content-type": "application/json" }, body: Buffer.from(JSON.stringify({ message: "Internal server error" })) };
    }
    return { status: 200, headers: { "content-type": "application/json" }, body: Buffer.from(JSON.stringify(result ?? null)) };
  }
  const headers = { ...(result.headers ?? {}) };
  for (const [name, values] of Object.entries(result.multiValueHeaders ?? {})) headers[name] = values;
  if (Array.isArray(result.cookies) && result.cookies.length) headers["set-cookie"] = result.cookies;
  const raw = result.body ?? "";
  const body = result.isBase64Encoded ? Buffer.from(raw, "base64") : Buffer.from(typeof raw === "string" ? raw : JSON.stringify(raw));
  return { status: Number(result.statusCode), headers, body };
}
