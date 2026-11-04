// Uses only Node's standard library plus `pg`, which the platform runtime image provides.
import http from "node:http";
import pg from "pg";

const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
const port = Number(process.argv[2] ?? process.env.PORT ?? 8080);

for (let attempt = 1; ; attempt += 1) {
  try {
    await pool.query("CREATE TABLE IF NOT EXISTS items (id text PRIMARY KEY, value text NOT NULL)");
    break;
  } catch (error) {
    if (attempt >= 60) throw error;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

const send = (response, status, body) => {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(body));
};

const readJson = (request) => new Promise((resolve, reject) => {
  let text = "";
  request.on("data", (chunk) => { text += chunk; });
  request.on("end", () => resolve(text ? JSON.parse(text) : {}));
  request.on("error", reject);
});

http.createServer(async (request, response) => {
  try {
    const { pathname } = new URL(request.url, "http://app");
    if (request.method === "GET" && pathname === "/health") return send(response, 200, { ok: true });
    if (request.method === "POST" && pathname === "/items") {
      const { id, value } = await readJson(request);
      await pool.query("INSERT INTO items (id, value) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET value = $2", [id, value]);
      return send(response, 201, { id });
    }
    const item = pathname.match(/^\/items\/([^/]+)$/);
    if (request.method === "GET" && item) {
      const { rows } = await pool.query("SELECT id, value FROM items WHERE id = $1", [decodeURIComponent(item[1])]);
      return rows[0] ? send(response, 200, rows[0]) : send(response, 404, { message: "not found" });
    }
    send(response, 404, { message: "not found" });
  } catch (error) {
    console.error(error);
    send(response, 500, { message: "internal error" });
  }
}).listen(port, () => console.log(`listening on :${port}`));
