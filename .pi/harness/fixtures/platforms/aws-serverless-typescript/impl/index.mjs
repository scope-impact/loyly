// Uses only the AWS SDK v3 already bundled in the nodejs22.x runtime image.
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient, GetCommand, PutCommand } from "@aws-sdk/lib-dynamodb";

const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const TableName = process.env.TABLE_NAME;
const json = (statusCode, body) => ({ statusCode, headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

export const handler = async (event) => {
  const method = event.requestContext.http.method;
  if (method === "POST" && event.rawPath === "/items") {
    const { id, value } = JSON.parse(event.body ?? "{}");
    await db.send(new PutCommand({ TableName, Item: { id, value } }));
    return json(201, { id });
  }
  if (method === "GET" && event.pathParameters?.id) {
    const { Item } = await db.send(new GetCommand({ TableName, Key: { id: event.pathParameters.id } }));
    return Item ? json(200, Item) : json(404, { message: "not found" });
  }
  return json(404, { message: "not found" });
};
