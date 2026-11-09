// Layout check (Playwright, the installed Chrome): at a phone (375) and a desktop (1280) width,
// light and dark, no view widens the page, neither as built (every "under the hood" folded) nor
// with every <details> open; the event storm (L0) scrolls inside its own frame and keeps its
// stickies legible; no event-storm cluster of an aggregate overlaps another or spills out of
// its columns. Exit 1 on any finding.
//   node layout.mjs <page.html> [review.html]
import { chromium } from 'playwright-core';
import { pathToFileURL } from 'node:url';
const pages = process.argv.slice(2);
const b = await chromium.launch({ channel: 'chrome' });
const bad = []; let n = 0;
const go = async (p, id) => { await p.evaluate(h => { location.hash = h; }, id); await p.waitForTimeout(30); };
const wider = p => p.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
for (const file of pages) {
  const url = pathToFileURL(file).href;
  for (const width of [375, 1280]) for (const scheme of ['light', 'dark']) {
    const p = await (await b.newContext({ viewport: { width, height: 900 }, colorScheme: scheme })).newPage();
    await p.goto(url); await p.waitForTimeout(300);
    const views = await p.evaluate(() => [...document.querySelectorAll('.view[id]')].map(v => v.id));
    const tag = `${file.split('/').pop()} ${width} ${scheme}`;
    for (const id of views) {
      await go(p, id);
      let w = await wider(p);
      if (w > 1) bad.push(`${tag}: #${id} is ${w}px wider than the screen`);
      await p.evaluate(id => document.getElementById(id).querySelectorAll('details').forEach(d => { d.open = true; }), id);
      await p.waitForTimeout(20);
      w = await wider(p);
      if (w > 1) bad.push(`${tag}: #${id}, every <details> open, is ${w}px wider than the screen`);
      n++;
      if (id === 'home') {
        const s = await p.evaluate(() => { const f = document.querySelector('#home .story-frame'); const t = f?.querySelector('.sk-t');
          return f && { frame: f.getBoundingClientRect().width, svg: f.querySelector('svg').getBoundingClientRect().width, font: t ? parseFloat(getComputedStyle(t).fontSize) : 0 }; });
        if (!s) bad.push(`${tag}: home has no event storm`);
        else { if (s.frame > width) bad.push(`${tag}: the event storm's frame is wider than the screen`);
               if (s.font < 11) bad.push(`${tag}: the event storm's stickies are set at ${s.font}px, too small to read`); }
      }
      if (width === 1280 && scheme === 'light' && id.startsWith('mod-')) {
        await p.waitForTimeout(60);
        const s = await p.evaluate((id) => {
          const clus = [...document.querySelectorAll('#' + CSS.escape(id) + ' .storm .clu')]; let ov = 0, sp = 0;
          for (const c of clus) { const r = c.getBoundingClientRect(); for (const k of c.querySelectorAll('*')) { const q = k.getBoundingClientRect(); if (q.width > 0 && (q.right > r.right + 2 || q.left < r.left - 2)) sp++; } }
          for (let i = 0; i < clus.length; i++) for (let j = i + 1; j < clus.length; j++) { const a = clus[i].getBoundingClientRect(), c = clus[j].getBoundingClientRect(); if (a.left < c.right - 2 && c.left < a.right - 2 && a.top < c.bottom - 2 && c.top < a.bottom - 2) ov++; }
          return { ov, sp };
        }, id);
        if (s.ov || s.sp) bad.push(`storm: #${id} ${s.ov} overlapping clusters, ${s.sp} elements outside their cluster`);
      }
      await p.evaluate(id => document.getElementById(id).querySelectorAll('details').forEach(d => { d.open = false; }), id);
    }
    await p.context().close();
  }
}
await b.close();
console.log(bad.length ? bad.join('\n') : `layout: ${n} view checks (375 and 1280, light and dark, folded and open) fit the screen; the event storm scrolls in its frame; storm boards have no overlaps`);
process.exit(bad.length ? 1 : 0);
