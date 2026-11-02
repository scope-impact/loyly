import { createInterface } from "node:readline";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";

export type CycleRequest = {
  requestId: string;
  selectionId: string;
  scope: readonly string[];
  targetStack: string;
  executionBackend: "host" | "gondolin";
};

export type CycleReceipt = { cycleId: string; taskId: number };
export type CycleOutcome = {
  status: "completed" | "failed";
  result?: unknown;
  error?: string;
};

type WorkerResponse = {
  id: number;
  ok: boolean;
  result?: unknown;
  error?: string;
};

type WorkerInvoker = (method: string, params: unknown) => Promise<unknown>;

export class OrcClient {
  private readonly invokeRequest: WorkerInvoker;
  private requestId = 0;

  constructor(invoke: WorkerInvoker) {
    this.invokeRequest = invoke;
  }

  prepareCut(sourceCommit: string, executionBackend: "host" | "gondolin"): Promise<CycleReceipt> {
    return this.invoke("prepare-cut", { sourceCommit, executionBackend }) as Promise<CycleReceipt>;
  }

  optimizePrompt(trainset: unknown[], valset: unknown[]): Promise<unknown> {
    return this.invoke("optimize-prompt", { trainset, valset });
  }

  startExternalPromptOptimization(trainset: unknown[], valset: unknown[]): Promise<any> {
    return this.invoke("start-external-prompt-optimization", { trainset, valset });
  }

  submitExternalPromptCandidate(optimizationId: string, prompt: string, proposer: string): Promise<any> {
    return this.invoke("submit-external-prompt-candidate", { optimizationId, prompt, proposer });
  }

  previewSelection(cut: string, slice: string, itemIds?: string[]): Promise<any> {
    return this.invoke("preview-selection", { cut, slice, ...(itemIds ? { itemIds } : {}) });
  }

  approveSelection(selectionId: string, selectionDigest: string): Promise<any> {
    return this.invoke("approve-selection", { selectionId, selectionDigest });
  }

  submit(request: CycleRequest): Promise<CycleReceipt> {
    return this.invoke("submit", request) as Promise<CycleReceipt>;
  }

  wait(taskId: number): Promise<CycleOutcome> {
    return this.invoke("wait", { taskId }) as Promise<CycleOutcome>;
  }


  private invoke(method: string, params: unknown): Promise<unknown> {
    return this.invokeRequest(method, params);
  }

  nextRequestId(): number {
    return ++this.requestId;
  }
}

export function spawnOrcClient(options: {
  projectRoot: string;
  command?: string;
}): { client: OrcClient; close: () => Promise<void> } {
  const child = spawn(options.command ?? ".pi/harness/orc-worker/run.sh", {
    cwd: options.projectRoot,
    stdio: ["pipe", "pipe", "inherit"],
  });
  const pending = new Map<number, { resolve: (value: unknown) => void; reject: (error: Error) => void }>();
  const lines = createInterface({ input: child.stdout });

  lines.on("line", (line) => {
    let response: WorkerResponse;
    try {
      response = JSON.parse(line) as WorkerResponse;
    } catch {
      return;
    }
    const waiter = pending.get(response.id);
    if (!waiter) return;
    pending.delete(response.id);
    if (response.ok) waiter.resolve(response.result);
    else waiter.reject(new Error(response.error ?? "ORC worker request failed"));
  });

  const rejectAll = (error: Error) => {
    for (const waiter of pending.values()) waiter.reject(error);
    pending.clear();
  };
  child.once("error", (error) => rejectAll(error));
  child.once("exit", (code, signal) => {
    rejectAll(new Error(`ORC worker exited (code=${code ?? "none"}, signal=${signal ?? "none"})`));
  });

  const client = new OrcClient((method, params) => {
    const id = client.nextRequestId();
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      child.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
    });
  });

  return {
    client,
    async close() {
      if (child.exitCode !== null || child.signalCode !== null) return;
      child.stdin.end();
      await new Promise<void>((resolve) => child.once("exit", () => resolve()));
    },
  };
}
