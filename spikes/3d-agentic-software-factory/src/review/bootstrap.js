import "./review.css";

const worker = new Worker(new URL("./sqlite-worker.js", import.meta.url), { type: "module" });
const pending = new Map();
let sequence = 0;

worker.onmessage = ({ data }) => {
  const request = pending.get(data.id);
  if (!request) return;
  pending.delete(data.id);
  data.ok ? request.resolve(data.result) : request.reject(new Error(data.error));
};

worker.onerror = (event) => {
  for (const request of pending.values()) request.reject(new Error(event.message || "Review database worker failed"));
  pending.clear();
};

const rpc = (op, payload = {}) => new Promise((resolve, reject) => {
  const id = `review-${++sequence}`;
  pending.set(id, { resolve, reject });
  worker.postMessage({ id, op, payload });
});

const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (char) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
}[char]));
const formatTime = (value) => value ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "";
const uid = () => crypto.randomUUID();
const digestValue = (value) => {
  let hash = 2166136261;
  for (const char of String(value ?? "")) {
    hash ^= char.codePointAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return `fnv1a-${(hash >>> 0).toString(16).padStart(8, "0")}`;
};

const state = {
  ready: false,
  reviewMode: false,
  panelOpen: false,
  view: "target",
  selected: null,
  thread: null,
  threads: [],
  status: null,
  author: "Local reviewer",
  targetIndex: new Map(),
};

const tabs = document.getElementById("tabs");
const toggle = document.createElement("button");
toggle.type = "button";
toggle.className = "review-toggle";
toggle.setAttribute("aria-pressed", "false");
toggle.innerHTML = `Comment <b hidden>0</b>`;
tabs.append(toggle);

document.body.insertAdjacentHTML("beforeend", `
  <div class="review-scrim" hidden></div>
  <aside class="review-panel" aria-label="Event model review" hidden>
    <header class="review-head">
      <div><h2>Model review</h2><p class="muted">Local evidence only—never model or builder input.</p></div>
      <button class="review-icon" type="button" data-review-close aria-label="Close review panel">×</button>
    </header>
    <div class="review-meta">
      <div class="review-storage"><span><strong>Starting SQLite</strong><br><small class="muted">Opening local review storage</small></span><button type="button" data-review-persist>Keep on device</button></div>
      <label class="review-author"><span>Reviewer</span><input data-review-author maxlength="80" value="Local reviewer"></label>
      <div class="review-tabs" role="tablist"><button type="button" role="tab" data-review-view="target" aria-selected="true">Selected target</button><button type="button" role="tab" data-review-view="all" aria-selected="false">All comments</button></div>
    </div>
    <div class="review-content"></div>
    <footer class="review-footer"><button type="button" data-review-export>Export JSON</button><button type="button" data-review-import>Import JSON</button><input type="file" accept="application/json" data-review-file hidden></footer>
  </aside>
  <div class="review-live" role="status" aria-live="polite"></div>
`);

const panel = document.querySelector(".review-panel");
const scrim = document.querySelector(".review-scrim");
const content = document.querySelector(".review-content");
const live = document.querySelector(".review-live");
const authorInput = document.querySelector("[data-review-author]");
const storage = document.querySelector(".review-storage");
const countBadge = toggle.querySelector("b");

const ensureToggle = () => { if (!toggle.isConnected) tabs.append(toggle); };
new MutationObserver(ensureToggle).observe(tabs, { childList: true });

function announce(message) {
  live.textContent = "";
  requestAnimationFrame(() => { live.textContent = message; });
}

function openPanel() {
  state.panelOpen = true;
  panel.hidden = false;
  scrim.hidden = false;
  document.body.classList.add("review-panel-open");
  renderPanel();
}

function closePanel() {
  state.panelOpen = false;
  panel.hidden = true;
  scrim.hidden = true;
  document.body.classList.remove("review-panel-open");
  toggle.focus();
}

function targetFrom(element) {
  return {
    key: element.dataset.reviewId,
    kind: element.dataset.reviewKind || "model target",
    label: element.dataset.reviewLabel || element.textContent.trim().slice(0, 120),
    digest: element.dataset.reviewDigest || "unversioned",
    sourceFile: element.dataset.reviewSource || "",
    sourceCommit: element.dataset.reviewCommit || "unknown",
    modelDigest: element.dataset.reviewModel || "unknown",
    snapshot: element.textContent.trim().replace(/\s+/g, " ").slice(0, 800),
    element,
  };
}

function classify(thread) {
  const currentDigest = state.targetIndex.get(thread.target_key);
  if (!currentDigest) return "orphaned";
  return currentDigest === thread.target_digest ? "unchanged" : "changed";
}

function indexTargets(model) {
  const index = new Map();
  const add = (key, digest) => index.set(key, digest || "unversioned");
  for (const module of model.modules || []) {
    add(`allium:${module.id}`, module.digest);
    for (const rule of module.rules || []) {
      const base = `allium:${module.id}:rule:${rule.name}`;
      add(base, rule.digest);
      add(`${base}:actor`, rule.digest);
      add(`${base}:boundary`, module.digest);
      (rule.requires || []).forEach((requirement, position) => {
        const digest = rule.requirementDigests?.[position] || digestValue(requirement);
        add(`${base}:requires:${digest}`, digest);
      });
      (rule.ensures || []).forEach((effect, position) => add(`${base}:effect:${position + 1}`, digestValue(effect)));
    }
  }
  for (const scenario of model.scenarios || []) {
    const identity = scenario.item ? `item:${scenario.item}:${digestValue(scenario.title)}` : `scenario:${digestValue(scenario.title)}`;
    add(`gherkin:${scenario.module}:${identity}`, scenario.digest);
  }
  for (const [kind, blocks] of Object.entries(model.eventModel || {})) {
    if (kind === "flows") {
      for (const [id, block] of Object.entries(blocks || {})) add(`grain:flow:${id}`, digestValue(JSON.stringify(block)));
    } else {
      for (const [id, block] of Object.entries(blocks || {})) add(`grain:${kind}:${id}`, digestValue(JSON.stringify(block)));
    }
  }
  for (const diagram of model.c4 || []) add(`c4:view:${digestValue(diagram.title)}`, diagram.digest);
  state.targetIndex = index;
}

function clearSelected() {
  document.querySelectorAll('[data-review-selected="true"]').forEach((element) => delete element.dataset.reviewSelected);
}

async function selectTarget(element) {
  clearSelected();
  element.dataset.reviewSelected = "true";
  state.selected = targetFrom(element);
  state.thread = await rpc("getTarget", { targetKey: state.selected.key });
  state.view = "target";
  openPanel();
  renderPanel();
}

function setReviewMode(enabled) {
  state.reviewMode = enabled;
  toggle.setAttribute("aria-pressed", String(enabled));
  document.body.classList.toggle("review-mode", enabled);
  refreshTargets();
  if (!enabled) {
    clearSelected();
    closePanel();
  } else {
    announce("Review mode on. Choose a model element to comment on.");
  }
}

function refreshTargets() {
  document.querySelectorAll("[data-review-id]").forEach((element) => {
    if (state.reviewMode) {
      element.tabIndex = 0;
      element.setAttribute("aria-label", `Review ${element.dataset.reviewLabel || element.textContent.trim().slice(0, 80)}`);
    } else {
      element.removeAttribute("tabindex");
      element.removeAttribute("aria-label");
    }
  });
  applyThreadMarkers();
}

function applyThreadMarkers() {
  const byTarget = new Map(state.threads.map((thread) => [thread.target_key, thread]));
  document.querySelectorAll("[data-review-id]").forEach((element) => {
    const thread = byTarget.get(element.dataset.reviewId);
    element.dataset.reviewCount = thread ? String(thread.comments?.length || thread.comment_count || 1) : "0";
    if (thread) element.dataset.reviewState = classify(thread);
    else delete element.dataset.reviewState;
  });
}

function commentMarkup(comment, thread) {
  return `<article class="review-comment ${comment.parent_comment_id ? "reply" : ""}"><header><b>${escapeHtml(comment.author_id)}</b><time>${escapeHtml(formatTime(comment.created_at))}</time></header><p>${escapeHtml(comment.body)}</p>${thread.comments.some((item) => item.parent_comment_id === comment.id) ? "" : `<button type="button" data-reply-to="${escapeHtml(comment.id)}">Reply</button>`}</article>`;
}

function targetView() {
  if (!state.selected) return `<div class="review-empty">Turn on Review, then choose a command, rule, refusal, scenario, Grain block, or C4 view.</div>`;
  const thread = state.thread;
  const targetState = thread ? classify(thread) : "new";
  const issueBody = encodeURIComponent(`Review target: ${state.selected.key}\nSource: ${state.selected.sourceFile}\nSource commit: ${state.selected.sourceCommit}\nModel digest: ${state.selected.modelDigest}\n\nDecision:\n`);
  return `<div class="review-target"><span class="kind">${escapeHtml(state.selected.kind)}</span><h3>${escapeHtml(state.selected.label)}</h3><code>${escapeHtml(state.selected.key)}</code><span class="review-state ${targetState}">${escapeHtml(targetState)}</span></div>
    <div class="review-thread">${thread ? thread.comments.map((comment) => commentMarkup(comment, thread)).join("") : ""}
      <form class="review-form"><label for="review-comment">${thread ? "Add to this thread" : "Add a comment"}</label><textarea id="review-comment" required maxlength="5000" placeholder="Describe the ambiguity, change, or decision needed."></textarea><button class="send" type="submit">${thread ? "Add comment" : "Start thread"}</button></form>
      ${thread ? `<div class="review-actions"><button type="button" data-thread-status="${thread.status === "open" ? "resolved" : "open"}">${thread.status === "open" ? "Resolve" : "Reopen"}</button><a href="https://github.com/scope-impact/loyly/issues/new?title=${encodeURIComponent(`Review ${state.selected.label}`)}&body=${issueBody}" target="_blank" rel="noreferrer">Promote to GitHub issue</a></div>` : ""}
    </div>`;
}

function allView() {
  if (!state.threads.length) return `<div class="review-empty">No local review threads yet.</div>`;
  return `<div class="review-list">${state.threads.map((thread) => `<button type="button" class="review-list-item" data-thread-target="${escapeHtml(thread.target_key)}"><b>${escapeHtml(thread.target_label)}</b><span>${escapeHtml(thread.target_kind)} · ${escapeHtml(classify(thread))}</span><em>${escapeHtml(thread.status)} · ${thread.comments.length}</em></button>`).join("")}</div>`;
}

function renderPanel() {
  document.querySelectorAll("[data-review-view]").forEach((button) => button.setAttribute("aria-selected", String(button.dataset.reviewView === state.view)));
  content.innerHTML = state.view === "all" ? allView() : targetView();
}

async function refreshThreads() {
  state.threads = await rpc("listThreads");
  const openCount = state.threads.filter((thread) => thread.status === "open").length;
  countBadge.hidden = openCount === 0;
  countBadge.textContent = String(openCount);
  toggle.setAttribute("aria-label", openCount ? `Comment, ${openCount} open threads` : "Comment");
  applyThreadMarkers();
  if (state.panelOpen) renderPanel();
}

async function saveComment(body, parentCommentId = null) {
  if (!state.selected || !body.trim()) return;
  state.thread = await rpc("createComment", {
    threadId: state.thread?.id || uid(),
    commentId: uid(),
    parentCommentId,
    body,
    author: state.author,
    targetKey: state.selected.key,
    targetKind: state.selected.kind,
    targetLabel: state.selected.label,
    targetDigest: state.selected.digest,
    targetSnapshot: state.selected.snapshot,
    sourceFile: state.selected.sourceFile,
    sourceCommit: state.selected.sourceCommit,
    modelDigest: state.selected.modelDigest,
  });
  await refreshThreads();
  renderPanel();
  announce("Comment saved in local SQLite.");
}

async function initialize() {
  const modelResponse = await fetch(new URL("event-model.json", location.href), { headers: { Accept: "application/json" } });
  if (!modelResponse.ok) throw new Error(`Review target index unavailable (${modelResponse.status})`);
  indexTargets(await modelResponse.json());
  state.status = await rpc("status");
  state.author = state.status.authorName;
  authorInput.value = state.author;
  storage.dataset.mode = state.status.storageMode;
  storage.querySelector("strong").textContent = state.status.storageMode === "opfs" ? "Browser SQLite" : "Session SQLite";
  storage.querySelector("small").textContent = state.status.storageDetail;
  await refreshThreads();
  state.ready = true;
}

toggle.addEventListener("click", () => setReviewMode(!state.reviewMode));
document.querySelector("[data-review-close]").addEventListener("click", closePanel);
scrim.addEventListener("click", closePanel);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    if (state.panelOpen) closePanel();
    else if (state.reviewMode) setReviewMode(false);
  }
  if (state.reviewMode && (event.key === "Enter" || event.key === " ")) {
    const target = event.target.closest?.("[data-review-id]");
    if (target && !event.target.closest(".review-panel")) { event.preventDefault(); selectTarget(target); }
  }
});
document.addEventListener("click", (event) => {
  if (!state.reviewMode || event.target.closest(".review-panel,.review-toggle")) return;
  const target = event.target.closest("[data-review-id]");
  if (target) { event.preventDefault(); selectTarget(target); }
}, true);
panel.addEventListener("submit", async (event) => {
  if (!event.target.matches(".review-form")) return;
  event.preventDefault();
  const textarea = event.target.querySelector("textarea");
  await saveComment(textarea.value, event.target.dataset.parent || null);
});
panel.addEventListener("click", async (event) => {
  const viewButton = event.target.closest("[data-review-view]");
  if (viewButton) { state.view = viewButton.dataset.reviewView; renderPanel(); return; }
  const statusButton = event.target.closest("[data-thread-status]");
  if (statusButton && state.thread) {
    state.thread = await rpc("setStatus", { threadId: state.thread.id, status: statusButton.dataset.threadStatus });
    await refreshThreads(); announce(`Thread ${state.thread.status}.`); return;
  }
  const replyButton = event.target.closest("[data-reply-to]");
  if (replyButton) {
    const form = document.createElement("form");
    form.className = "review-form"; form.dataset.parent = replyButton.dataset.replyTo;
    form.innerHTML = `<label>Reply</label><textarea required maxlength="5000"></textarea><button class="send" type="submit">Add reply</button>`;
    replyButton.replaceWith(form); form.querySelector("textarea").focus(); return;
  }
  const targetButton = event.target.closest("[data-thread-target]");
  if (targetButton) {
    const element = document.querySelector(`[data-review-id="${CSS.escape(targetButton.dataset.threadTarget)}"]`);
    if (element) { element.scrollIntoView({ block: "center" }); await selectTarget(element); }
    else {
      const thread = state.threads.find((item) => item.target_key === targetButton.dataset.threadTarget);
      state.selected = { key: thread.target_key, kind: thread.target_kind, label: thread.target_label, digest: thread.target_digest, sourceFile: thread.source_file, sourceCommit: thread.source_commit, modelDigest: thread.model_digest, snapshot: thread.target_snapshot, element: null };
      state.thread = thread; state.view = "target"; renderPanel();
    }
  }
});
authorInput.addEventListener("change", async () => {
  state.author = await rpc("setAuthor", { authorName: authorInput.value });
  authorInput.value = state.author;
  announce(`Reviewer name set to ${state.author}.`);
});
document.querySelector("[data-review-persist]").addEventListener("click", async (event) => {
  const granted = navigator.storage?.persist ? await navigator.storage.persist() : false;
  event.target.textContent = granted ? "Kept on device" : "Browser decides retention";
  announce(granted ? "Persistent browser storage granted." : "Persistent storage was not granted. Export reviews for backup.");
});
document.querySelector("[data-review-export]").addEventListener("click", async () => {
  const documentData = await rpc("exportReview");
  const url = URL.createObjectURL(new Blob([JSON.stringify(documentData, null, 2)], { type: "application/json" }));
  const link = document.createElement("a"); link.href = url; link.download = `loyly-review-${new Date().toISOString().slice(0, 10)}.json`; link.click(); URL.revokeObjectURL(url);
  announce("Review JSON exported.");
});
const fileInput = document.querySelector("[data-review-file]");
document.querySelector("[data-review-import]").addEventListener("click", () => fileInput.click());
fileInput.addEventListener("change", async () => {
  const file = fileInput.files?.[0]; if (!file) return;
  try { const result = await rpc("importReview", { document: JSON.parse(await file.text()) }); await refreshThreads(); announce(`${result.imported} review threads imported.`); }
  catch (error) { announce(`Import failed: ${error.message}`); }
  fileInput.value = "";
});

const observer = new MutationObserver(() => requestAnimationFrame(refreshTargets));
observer.observe(document.getElementById("view"), { childList: true, subtree: true });

initialize().catch((error) => {
  storage.dataset.mode = "memory";
  storage.querySelector("strong").textContent = "Review storage unavailable";
  storage.querySelector("small").textContent = error.message;
  toggle.disabled = true;
});
