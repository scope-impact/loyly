import assert from "node:assert/strict";
import test from "node:test";
import { matchRoute, parseTemplate, routes, tableDefinitions, toEvent, toResponse } from "./sam-template.mjs";

const template = parseTemplate(`
Transform: AWS::Serverless-2016-10-31
Resources:
  ItemsTable:
    Type: AWS::DynamoDB::Table
    Properties:
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions: [{AttributeName: id, AttributeType: S}]
      KeySchema: [{AttributeName: id, KeyType: HASH}]
  Sessions:
    Type: AWS::Serverless::SimpleTable
    Properties:
      TableName: sessions-local
      PrimaryKey: {Name: token, Type: String}
  ApiFunction:
    Type: AWS::Serverless::Function
    Properties:
      Environment:
        Variables:
          TABLE_NAME: !Ref ItemsTable
          ARN: !GetAtt ItemsTable.Arn
          JOINED: !Join ["-", [a, b]]
      Events:
        Read: {Type: HttpApi, Properties: {Path: "/items/{id}", Method: get}}
        Create: {Type: HttpApi, Properties: {Path: /items, Method: post}}
        Literal: {Type: HttpApi, Properties: {Path: /items/latest, Method: get}}
`);

test("intrinsic short forms parse to their long forms", () => {
  const vars = template.Resources.ApiFunction.Properties.Environment.Variables;
  assert.deepEqual(vars.TABLE_NAME, { Ref: "ItemsTable" });
  assert.deepEqual(vars.ARN, { "Fn::GetAtt": ["ItemsTable", "Arn"] });
  assert.deepEqual(vars.JOINED, { "Fn::Join": ["-", ["a", "b"]] });
});

test("tables use the logical ID unless a literal TableName is declared", () => {
  const tables = tableDefinitions(template);
  assert.deepEqual(tables.map((t) => t.TableName), ["ItemsTable", "sessions-local"]);
  assert.deepEqual(tables[1].KeySchema, [{ AttributeName: "token", KeyType: "HASH" }]);
  assert.equal(tables[0].BillingMode, "PAY_PER_REQUEST");
});

test("literal routes win over parameterized routes and methods must match", () => {
  const table = routes(template);
  assert.equal(matchRoute(table, "GET", "/items/latest").route.eventId, "Literal");
  const read = matchRoute(table, "GET", "/items/a%20b");
  assert.equal(read.route.eventId, "Read");
  assert.deepEqual(read.pathParameters, { id: "a b" });
  assert.equal(matchRoute(table, "DELETE", "/items/x"), undefined);
  assert.equal(matchRoute(table, "GET", "/nope"), undefined);
});

test("an HttpApi event without a path is the catch-all", () => {
  const t = parseTemplate(`
Resources:
  Fn:
    Type: AWS::Serverless::Function
    Properties:
      Events: {All: {Type: HttpApi}}
`);
  const match = matchRoute(routes(t), "PATCH", "/anything/here");
  assert.equal(match.route.catchAll, true);
  assert.equal(toEvent(match, { method: "PATCH", path: "/anything/here", query: "", headers: {}, body: Buffer.alloc(0) }).routeKey, "$default");
});

test("more than one function is refused in phase 1", () => {
  const t = parseTemplate(`
Resources:
  A: {Type: AWS::Serverless::Function, Properties: {Events: {E: {Type: HttpApi}}}}
  B: {Type: AWS::Serverless::Function, Properties: {Events: {E: {Type: HttpApi}}}}
`);
  assert.throws(() => routes(t), /exactly one/);
});

test("payload 2.0 events carry path parameters, query, cookies, and a text body", () => {
  const match = matchRoute(routes(template), "GET", "/items/7");
  const event = toEvent(match, {
    method: "GET", path: "/items/7", query: "x=1",
    headers: { "content-type": "application/json", cookie: "a=1; b=2" },
    body: Buffer.from('{"k":1}'),
  });
  assert.equal(event.version, "2.0");
  assert.equal(event.routeKey, "GET /items/{id}");
  assert.deepEqual(event.pathParameters, { id: "7" });
  assert.deepEqual(event.queryStringParameters, { x: "1" });
  assert.deepEqual(event.cookies, ["a=1", "b=2"]);
  assert.equal(event.body, '{"k":1}');
  assert.equal(event.isBase64Encoded, false);
  assert.equal(event.headers.cookie, undefined);
});

test("responses follow API Gateway payload rules", () => {
  const structured = toResponse("2.0", { statusCode: 201, headers: { "x-a": "1" }, body: "ok" });
  assert.equal(structured.status, 201);
  assert.equal(structured.body.toString(), "ok");
  const bare = toResponse("2.0", { id: 1 });
  assert.equal(bare.status, 200);
  assert.equal(bare.body.toString(), '{"id":1}');
  assert.equal(toResponse("2.0", { errorType: "Error", errorMessage: "boom" }).status, 502);
  assert.equal(toResponse("1.0", { id: 1 }).status, 502);
});
