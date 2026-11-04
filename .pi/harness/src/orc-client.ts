import { createInterface } from "node:readline";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";

export type CycleRequest = {
  requestId: string;
  selectionId: string;
  scope: readonly string[];
  targetStack: string;
  executionBackend: "host" | "gondolin";
};

export type CutReceipt = { cutId: string; taskId: number; executionBackend: "host" | "gondolin" };

export type OperationReceipt = {
  operation: "generation" | "regeneration";
  operationId: string;
  selectionId: string;
  parentCandidateId?: string;
  tickId: string;
  correlationId: string;
  taskId: number;
  targetStack: string;
  executionBackend: "host" | "gondolin";
};
export type CycleOutcome = {
  status: "completed" | "failed";
  result?: unknown;
  error?: string;
};

export type ExecutionStatus = {
  tickId: string;
  sheetId?: string;
  status: "not-started" | "running" | "completed" | "failed";
  stage?: string;
  attempt?: number;
  builderSessionRef?: string;
};

export type BuilderSessionView = {
  builderSessionRef: string;
  status: "running" | "completed" | "failed";
  entries: readonly unknown[];
  toolCalls: number;
  workspaceGeneration: readonly string[];
  progress: { stage: string; attempt: number };
};

export type OracleProposal = {
  "proposal-id": string;
  "proposal-digest": string;
  "generation-id": string;
  "selection-id": string;
  "selection-digest": string;
  "scenario-digests": readonly string[];
  "subject-digest": string;
  "judge-id": string;
  denominator: number;
  "normalized-evidence-digest": string;
  status: "proposed" | "approved" | "published";
  "approved-by"?: string;
  "descriptor-digest"?: string;
};

export type WorkflowContract = {
  workflowName: string;
  rootType: string;
  stages: string[];
  builderResultFields: string[];
  observationFields: string[];
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

  prepareCut(sourceCommit: string, executionBackend: "host" | "gondolin"): Promise<CutReceipt> {
    return this.invoke("prepare-cut", { sourceCommit, executionBackend }) as Promise<CutReceipt>;
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

  sliceComposition(cut: string): Promise<any> {
    return this.invoke("slice-composition", { cut });
  }

  previewSelection(cut: string, slice: string, itemIds?: string[]): Promise<any> {
    return this.invoke("preview-selection", { cut, slice, ...(itemIds ? { itemIds } : {}) });
  }

  approveSelection(selectionId: string, selectionDigest: string): Promise<any> {
    return this.invoke("approve-selection", { selectionId, selectionDigest });
  }

  oracleProposal(proposalId: string): Promise<OracleProposal | null> {
    return this.invoke("oracle-proposal", { proposalId }) as Promise<OracleProposal | null>;
  }

  approveOracleProposal(proposalId: string, proposalDigest: string, approvedBy: string): Promise<OracleProposal> {
    return this.invoke("approve-oracle-proposal", { proposalId, proposalDigest, approvedBy }) as Promise<OracleProposal>;
  }

  submitApprovedSlice(request: CycleRequest): Promise<OperationReceipt> {
    return this.invoke("submit-approved-slice", request) as Promise<OperationReceipt>;
  }

  wait(taskId: number): Promise<CycleOutcome> {
    return this.invoke("wait", { taskId }) as Promise<CycleOutcome>;
  }

  executionStatus(tickId: string): Promise<ExecutionStatus> {
    return this.invoke("execution-status", { tickId }) as Promise<ExecutionStatus>;
  }

  builderSession(builderSessionRef: string): Promise<BuilderSessionView> {
    return this.invoke("builder-session", { builderSessionRef }) as Promise<BuilderSessionView>;
  }

  measurement(cycleId: string): Promise<unknown> {
    return this.invoke("measurement", { cycleId });
  }

  workflowContract(): Promise<WorkflowContract> {
    return this.invoke("workflow-contract", {}) as Promise<WorkflowContract>;
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
  env?: NodeJS.ProcessEnv;
}): { client: OrcClient; close: () => Promise<void> } {
  const child = spawn(options.command ?? ".pi/harness/orc-worker/run.sh", {
    cwd: options.projectRoot,
    env: { ...process.env, ...options.env },
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
      await new Promise<void>((resolve) => {
        let settled = false;
        const finish = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timeout);
          resolve();
        };
        const timeout = setTimeout(() => child.kill("SIGTERM"), 2_000);
        child.once("exit", finish);
        child.stdin.end();
        if (child.exitCode !== null || child.signalCode !== null) finish();
      });
    },
  };
}
