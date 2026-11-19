import http from 'node:http';
import fs from 'node:fs';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
const port = Number(process.env.PORT || 18727);
const allowed = new Set(['openai-codex/gpt-6.1-sol', 'openai-codex/gpt-6.1-terra']);
const hash = s => createHash('sha256').update(s).digest('hex');
const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {res.writeHead(200);res.end('ok');return;}
  if (req.method !== 'POST' || !req.url?.endsWith('/chat/completions')) {res.writeHead(404);res.end();return;}
  let raw = '';
  for await (const chunk of req) {raw += chunk; if (raw.length > 2_000_000) {res.writeHead(413);res.end();return;}}
  let data;
  try {data = JSON.parse(raw);} catch {res.writeHead(400);res.end();return;}
  const model = String(data.model || '');
  if (!allowed.has(model)) {res.writeHead(400);res.end('unsupported model');return;}
  const prompt = (data.messages || []).map(m => `${m.role.toUpperCase()}:\n${typeof m.content === 'string' ? m.content : JSON.stringify(m.content)}`).join('\n\n');
  const renderFixture = process.env.LOYLY_RENDER_FIXTURE_FILE;
  if (model === 'openai-codex/gpt-6.1-sol' && renderFixture && prompt.includes('builder-instruction')) {
    const instruction = fs.readFileSync(renderFixture, 'utf8');
    const content = `[[ ## builder-instruction ## ]]\n${instruction}\n[[ ## completed ## ]]`;
    console.error(JSON.stringify({model, kind:'pinned-render', promptSha256:hash(prompt), responseSha256:hash(content)}));
    const body = {id:`render-${Date.now()}`,object:'chat.completion',model,choices:[{index:0,finish_reason:'stop',message:{role:'assistant',content}}]};
    res.writeHead(200, {'content-type':'application/json'});res.end(JSON.stringify(body));return;
  }
  const args = ['--model', model, '--print', '--no-session', '--no-tools', '--no-extensions', '--no-mcp', '--no-skills', '--no-context-files', '--no-approve', '--thinking', 'off', '--', prompt];
  const t = Date.now();
  const child = spawn('pi', args, {stdio:['ignore','pipe','pipe']});
  let out = '', err = '';
  res.on('close', () => {if (!res.writableEnded) child.kill('SIGTERM');});
  child.stdout.on('data', c => {out += c; if (out.length > 2_000_000) child.kill('SIGTERM');});
  child.stderr.on('data', c => {err += c;});
  const timer = setTimeout(() => child.kill('SIGTERM'), 590_000);
  child.on('error', e => {clearTimeout(timer); if (!res.headersSent) {res.writeHead(502);res.end(e.message);}});
  child.on('close', code => {
    clearTimeout(timer);
    console.error(JSON.stringify({model, promptSha256:hash(prompt), responseSha256:hash(out), code, elapsedMs:Date.now()-t, error:err.slice(0,500)}));
    if (res.writableEnded) return;
    if (code !== 0) {res.writeHead(502);res.end('model failed');return;}
    const body = {id:`pi-${Date.now()}`,object:'chat.completion',model,choices:[{index:0,finish_reason:'stop',message:{role:'assistant',content:out}}]};
    res.writeHead(200, {'content-type':'application/json'});res.end(JSON.stringify(body));
  });
});
server.listen(port, '127.0.0.1', () => console.error(`pi model bridge ${port}`));
