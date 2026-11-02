export type ExecutionBackend = "host" | "gondolin";

export function executionBackend(value: unknown): ExecutionBackend {
  if (value === "host" || value === "gondolin") return value;
  throw new Error(`unsupported execution backend: ${String(value)}`);
}
