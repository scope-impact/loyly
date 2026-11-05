import sqlite3InitModule from "@sqlite.org/sqlite-wasm";

const DB_PATH = "/loyly-eventstorm-review.sqlite3";
const ARTIFACT_ID = "loyly:pi-harness:event-model";
let sqlite3;
let db;
let storageMode = "starting";
let storageDetail = "Opening local review database";

const rows = (sql, bind = []) => db.exec({
  sql,
  bind,
  rowMode: "object",
  returnValue: "resultRows",
});

const run = (sql, bind = []) => db.exec({ sql, bind });

function migrate() {
  run("PRAGMA foreign_keys = ON");
  run(`
    CREATE TABLE IF NOT EXISTS review_threads (
      id TEXT PRIMARY KEY,
      artifact_id TEXT NOT NULL,
      target_key TEXT NOT NULL,
      target_kind TEXT NOT NULL,
      target_label TEXT NOT NULL,
      target_digest TEXT NOT NULL,
      target_snapshot TEXT NOT NULL,
      source_file TEXT,
      source_commit TEXT NOT NULL,
      model_digest TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','resolved','superseded')),
      created_by TEXT NOT NULL,
      created_at TEXT NOT NULL,
      resolved_at TEXT,
      accepted_commit TEXT,
      UNIQUE(artifact_id, target_key)
    );
    CREATE TABLE IF NOT EXISTS review_comments (
      id TEXT PRIMARY KEY,
      thread_id TEXT NOT NULL REFERENCES review_threads(id) ON DELETE CASCADE,
      parent_comment_id TEXT REFERENCES review_comments(id) ON DELETE SET NULL,
      body TEXT NOT NULL,
      author_id TEXT NOT NULL,
      created_at TEXT NOT NULL,
      edited_at TEXT
    );
    CREATE TABLE IF NOT EXISTS review_meta (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS review_comments_thread ON review_comments(thread_id, created_at);
    PRAGMA user_version = 1;
  `);
}

async function initialize() {
  sqlite3 = await sqlite3InitModule({
    print: () => {},
    printErr: (...args) => console.warn("SQLite", ...args),
  });
  try {
    const pool = await sqlite3.installOpfsSAHPoolVfs({
      name: "loyly-review-sahpool",
      directory: ".loyly-review-sahpool",
      initialCapacity: 6,
      preserveOnInitFailure: true,
    });
    db = new pool.OpfsSAHPoolDb(DB_PATH);
    storageMode = "opfs";
    storageDetail = "Saved in browser SQLite using OPFS";
  } catch (error) {
    console.warn("Persistent review storage unavailable", error);
    db = new sqlite3.oo1.DB(":memory:", "c");
    storageMode = "memory";
    storageDetail = `Session only: ${error?.message || "OPFS unavailable"}`;
  }
  migrate();
  return status();
}

const ready = initialize();

function status() {
  const count = rows("SELECT COUNT(*) AS count FROM review_threads WHERE status = 'open'")[0]?.count || 0;
  return {
    storageMode,
    storageDetail,
    sqliteVersion: sqlite3.version.libVersion,
    schemaVersion: rows("PRAGMA user_version")[0]?.user_version || 1,
    openCount: Number(count),
    authorName: rows("SELECT value FROM review_meta WHERE key = 'author_name'")[0]?.value || "Local reviewer",
  };
}

function commentsFor(threadId) {
  return rows("SELECT * FROM review_comments WHERE thread_id = ? ORDER BY created_at, id", [threadId]);
}

function hydrate(thread) {
  return { ...thread, comments: commentsFor(thread.id) };
}

function listThreads() {
  return rows(`
    SELECT t.*, COUNT(c.id) AS comment_count
    FROM review_threads t
    LEFT JOIN review_comments c ON c.thread_id = t.id
    WHERE t.artifact_id = ?
    GROUP BY t.id
    ORDER BY CASE t.status WHEN 'open' THEN 0 WHEN 'resolved' THEN 1 ELSE 2 END, t.created_at DESC
  `, [ARTIFACT_ID]).map(hydrate);
}

function getTarget({ targetKey }) {
  const thread = rows("SELECT * FROM review_threads WHERE artifact_id = ? AND target_key = ?", [ARTIFACT_ID, targetKey])[0];
  return thread ? hydrate(thread) : null;
}

function createComment(payload) {
  const now = new Date().toISOString();
  let thread = rows("SELECT * FROM review_threads WHERE artifact_id = ? AND target_key = ?", [ARTIFACT_ID, payload.targetKey])[0];
  run("BEGIN IMMEDIATE");
  try {
    if (!thread) {
      run(`INSERT INTO review_threads
        (id, artifact_id, target_key, target_kind, target_label, target_digest, target_snapshot,
         source_file, source_commit, model_digest, status, created_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)`, [
        payload.threadId, ARTIFACT_ID, payload.targetKey, payload.targetKind, payload.targetLabel,
        payload.targetDigest, payload.targetSnapshot, payload.sourceFile || null, payload.sourceCommit,
        payload.modelDigest, payload.author, now,
      ]);
      thread = { id: payload.threadId };
    } else if (thread.status !== "open") {
      run("UPDATE review_threads SET status = 'open', resolved_at = NULL WHERE id = ?", [thread.id]);
    }
    run(`INSERT INTO review_comments
      (id, thread_id, parent_comment_id, body, author_id, created_at)
      VALUES (?, ?, ?, ?, ?, ?)`, [
      payload.commentId, thread.id, payload.parentCommentId || null, payload.body.trim(), payload.author, now,
    ]);
    run("COMMIT");
  } catch (error) {
    run("ROLLBACK");
    throw error;
  }
  return getTarget({ targetKey: payload.targetKey });
}

function setStatus({ threadId, status: nextStatus, acceptedCommit = null }) {
  if (!["open", "resolved", "superseded"].includes(nextStatus)) throw new Error("Invalid review status");
  const resolvedAt = nextStatus === "open" ? null : new Date().toISOString();
  run("UPDATE review_threads SET status = ?, resolved_at = ?, accepted_commit = ? WHERE id = ?", [nextStatus, resolvedAt, acceptedCommit, threadId]);
  const thread = rows("SELECT * FROM review_threads WHERE id = ?", [threadId])[0];
  return thread ? hydrate(thread) : null;
}

function setAuthor({ authorName }) {
  const value = String(authorName || "").trim() || "Local reviewer";
  run(`INSERT INTO review_meta(key, value) VALUES('author_name', ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value`, [value]);
  return value;
}

function exportReview() {
  return {
    format: "loyly-eventstorm-review",
    version: 1,
    artifactId: ARTIFACT_ID,
    exportedAt: new Date().toISOString(),
    threads: listThreads(),
  };
}

function importReview({ document }) {
  if (!document || document.format !== "loyly-eventstorm-review" || document.version !== 1 || !Array.isArray(document.threads)) {
    throw new Error("Unsupported review document");
  }
  run("BEGIN IMMEDIATE");
  try {
    for (const thread of document.threads) {
      const existing = rows("SELECT id FROM review_threads WHERE artifact_id = ? AND target_key = ?", [ARTIFACT_ID, thread.target_key])[0];
      const localThreadId = existing?.id || thread.id;
      run(`INSERT INTO review_threads
        (id, artifact_id, target_key, target_kind, target_label, target_digest, target_snapshot,
         source_file, source_commit, model_digest, status, created_by, created_at, resolved_at, accepted_commit)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(artifact_id, target_key) DO UPDATE SET
          target_label = excluded.target_label,
          target_digest = excluded.target_digest,
          target_snapshot = excluded.target_snapshot,
          status = excluded.status,
          resolved_at = excluded.resolved_at,
          accepted_commit = excluded.accepted_commit`, [
        localThreadId, ARTIFACT_ID, thread.target_key, thread.target_kind, thread.target_label,
        thread.target_digest, thread.target_snapshot, thread.source_file || null,
        thread.source_commit, thread.model_digest, thread.status, thread.created_by,
        thread.created_at, thread.resolved_at || null, thread.accepted_commit || null,
      ]);
      for (const comment of thread.comments || []) {
        run(`INSERT INTO review_comments
          (id, thread_id, parent_comment_id, body, author_id, created_at, edited_at)
          VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING`, [
          comment.id, localThreadId, comment.parent_comment_id || null, comment.body,
          comment.author_id, comment.created_at, comment.edited_at || null,
        ]);
      }
    }
    run("COMMIT");
  } catch (error) {
    run("ROLLBACK");
    throw error;
  }
  return { imported: document.threads.length, ...status() };
}

const operations = {
  status,
  listThreads,
  getTarget,
  createComment,
  setStatus,
  setAuthor,
  exportReview,
  importReview,
};

self.onmessage = async ({ data }) => {
  const { id, op, payload = {} } = data || {};
  try {
    await ready;
    if (!operations[op]) throw new Error(`Unknown review operation: ${op}`);
    self.postMessage({ id, ok: true, result: operations[op](payload) });
  } catch (error) {
    self.postMessage({ id, ok: false, error: error?.message || String(error) });
  }
};
