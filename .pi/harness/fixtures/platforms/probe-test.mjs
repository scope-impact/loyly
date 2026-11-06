// One black-box probe for every platform: the same public HTTP seam must behave the same
// whether it is served by a Lambda behind the SAM gateway or by a container server.
const base = process.env.BASE_URL ?? "http://app:8080";

async function call(method, path, body) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: body ? { "content-type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : null };
}

const checks = [];
function check(name, ok, detail) {
  checks.push({ name, ok });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${ok ? "" : ` ${JSON.stringify(detail)}`}`);
}

const created = await call("POST", "/items", { id: "sauna-1", value: "löyly" });
check("create returns 201", created.status === 201, created);

const read = await call("GET", "/items/sauna-1");
check("read returns the stored value", read.status === 200 && read.json?.value === "löyly", read);

const missing = await call("GET", "/items/missing");
check("unknown item returns 404", missing.status === 404, missing);

const parallel = await Promise.all(Array.from({ length: 8 }, () => call("GET", "/items/sauna-1")));
check("8 concurrent reads all succeed", parallel.every((r) => r.status === 200), parallel.map((r) => r.status));

const failed = checks.filter((c) => !c.ok).length;
console.log(`${checks.length - failed}/${checks.length} probe checks passed`);
process.exit(failed ? 1 : 0);
