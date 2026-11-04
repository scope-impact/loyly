const port = process.env.SAM_GATEWAY_READINESS_PORT ?? "8081";
fetch(`http://127.0.0.1:${port}/`).then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1));
