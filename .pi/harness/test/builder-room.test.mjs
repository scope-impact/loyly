import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { openBuilderRoom } from "../src/builder-room.ts";

test("builder room rejects overlapping or escaping scopes before VM creation", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-room-"));
  try {
    await mkdir(path.join(root, "cut"), { recursive: true });
    await assert.rejects(
      openBuilderRoom({ backend: "gondolin", cutsRoot: root, cutId: "cut", scope: ["src", "src/app"] }),
      /overlap/,
    );
    await assert.rejects(
      openBuilderRoom({ backend: "gondolin", cutsRoot: root, cutId: "cut", scope: ["../escape"] }),
      /invalid builder scope/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
