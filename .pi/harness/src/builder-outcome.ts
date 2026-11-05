type Message = { role: string; stopReason?: string; errorMessage?: string };

/**
 * Pi resolves a prompt even when its last turn ended on a model error (after its own
 * retries). That builder did not finish: say why, so the cycle reports an inconclusive
 * builder rather than a builder that chose to change nothing.
 */
export function modelFailure(messages: readonly Message[]): string | undefined {
  const last = messages.findLast((message) => message.role === "assistant");
  if (last?.stopReason !== "error" && last?.stopReason !== "aborted") return undefined;
  return `builder session ended on a model error: ${last.errorMessage || last.stopReason}`;
}
