// Layout check (Playwright, the installed Chrome): no view widens a phone screen, and no
// event-storm cluster overlaps another or spills out of its columns. Exit 1 on any finding.
import { chromium, devices } from 'playwright-core';
import { pathToFileURL } from 'node:url';
const page = pathToFileURL(process.argv[2]).href;
const b = await chromium.launch({ channel: 'chrome' });
const phone = await (await b.newContext({ ...devices['iPhone 13'] })).newPage();
const desk = await (await b.newContext({ viewport: { width: 1440, height: 1000 } })).newPage();
await phone.goto(page);
const views = await phone.evaluate(() => [...document.querySelectorAll('.view[id]')].map(v => v.id));
const bad = [];
for (const id of views) {
  await phone.goto(page + '#' + id);
  const w = await phone.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (w > 1) bad.push(`phone: #${id} is ${w}px wider than the screen`);
  if (!id.startsWith('mod-')) continue;
  await desk.goto(page + '#' + id);
  const s = await desk.evaluate((id) => {
    const clus = [...document.querySelectorAll('#' + CSS.escape(id) + ' .storm .clu')]; let ov = 0, sp = 0;
    for (const c of clus) { const r = c.getBoundingClientRect(); for (const k of c.querySelectorAll('*')) { const q = k.getBoundingClientRect(); if (q.width > 0 && (q.right > r.right + 2 || q.left < r.left - 2)) sp++; } }
    for (let i = 0; i < clus.length; i++) for (let j = i + 1; j < clus.length; j++) { const a = clus[i].getBoundingClientRect(), c = clus[j].getBoundingClientRect(); if (a.left < c.right - 2 && c.left < a.right - 2 && a.top < c.bottom - 2 && c.top < a.bottom - 2) ov++; }
    return { ov, sp };
  }, id);
  if (s.ov || s.sp) bad.push(`storm: #${id} ${s.ov} overlapping clusters, ${s.sp} elements outside their cluster`);
}
await b.close();
console.log(bad.length ? bad.join('\n') : `layout: ${views.length} views fit a phone; storm boards have no overlaps`);
process.exit(bad.length ? 1 : 0);
