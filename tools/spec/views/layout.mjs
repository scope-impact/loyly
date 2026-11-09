// Layout check (Playwright, the installed Chrome): at a phone (375) and a desktop (1280) width,
// light and dark, no view widens the page, neither as built (every "under the hood" folded) nor
// with every <details> open; the event storm (L0) scrolls inside its own frame and keeps its
// stickies legible; at 1280 it fits its frame without scrolling, no handoff arrow runs over a
// sticky, and no sticky text, trigger line or band header overlaps another; no event-storm cluster of an aggregate overlaps another or spills out of
// its columns; a deep link to an example folded under "N more examples" opens it and scrolls to it. Exit 1 on any finding.
//   node layout.mjs <page.html> [review.html]
import { chromium } from 'playwright-core';
import { pathToFileURL } from 'node:url';
const pages = process.argv.slice(2);
const b = await chromium.launch({ channel: 'chrome' });
const bad = []; let n = 0, deeps = 0, storm = '';
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
        if (width === 1280) {   // the whole storm fits a desktop page, legibly, and nothing in it collides
          const g = await p.evaluate(() => {
            const f = document.querySelector('#home .story-frame'), svg = f.querySelector('svg'), out = [];
            const scale = svg.getBoundingClientRect().width / svg.viewBox.baseVal.width;
            const font = parseFloat(getComputedStyle(svg.querySelector('.sk-t')).fontSize) * scale;
            if (f.scrollWidth > f.clientWidth + 1) out.push(`the event storm overflows its frame by ${f.scrollWidth - f.clientWidth}px`);
            if (font < 10.5) out.push(`the event storm is scaled so its stickies read at ${font.toFixed(1)}px`);
            const R = el => el.getBoundingClientRect();
            const sks = [...svg.querySelectorAll('.ev')].map(a => ({ a, r: R(a.querySelector('.sk')) }));
            const inside = (q, r, m) => q.left >= r.left - m && q.right <= r.right + m && q.top >= r.top - m && q.bottom <= r.bottom + m;
            const hit = (q, r, m) => q.left < r.right - m && r.left < q.right - m && q.top < r.bottom - m && r.top < q.bottom - m;
            // no arrow runs over a sticky: sample each arrow along its length, in screen space
            const m = svg.getScreenCTM(); let crossings = 0;
            for (const path of svg.querySelectorAll('path.arrow')) {
              const L = path.getTotalLength();
              for (let d = 0; d <= L; d += 2) { const pt = path.getPointAtLength(d), x = m.a * pt.x + m.c * pt.y + m.e, y = m.b * pt.x + m.d * pt.y + m.f;
                const s = sks.find(({ r }) => x > r.left + 2 && x < r.right - 2 && y > r.top + 2 && y < r.bottom - 2);
                if (s) { crossings++; out.push(`the arrow ${path.dataset.arrow} runs over the sticky ${s.a.dataset.event}`); break; } }
            }
            // no label overlaps: a sticky's text inside its sticky; its trigger lines clear of every other sticky and trigger
            const hints = [];
            for (const { a, r } of sks) {
              for (const t of a.querySelectorAll('.sk-t')) if (!inside(R(t), r, 1)) out.push(`the sticky ${a.dataset.event}: "${t.textContent}" spills out of it`);
              for (const t of a.querySelectorAll('.hint')) hints.push({ a, q: R(t), t });
            }
            for (const h of hints) {
              for (const { a, r } of sks) if (hit(h.q, r, 1)) out.push(`the trigger "${h.t.textContent}" of ${h.a.dataset.event} overlaps the sticky ${a.dataset.event}`);
              for (const k of hints) if (k !== h && k.a !== h.a && hit(h.q, k.q, 1)) out.push(`the triggers of ${h.a.dataset.event} and ${k.a.dataset.event} overlap`);
            }
            for (const t of svg.querySelectorAll('.lane-h')) for (const { a, r } of sks) if (hit(R(t), r, 1)) out.push(`the band header "${t.textContent}" overlaps the sticky ${a.dataset.event}`);
            return { out, arrows: svg.querySelectorAll('path.arrow').length, scale };
          });
          g.out.forEach(o => bad.push(`${tag}: ${o}`));
          if (!g.out.length && scheme === 'light') storm = `the event storm fits 1280 (scale ${g.scale.toFixed(2)}), ${g.arrows} handoff arrows cross no sticky, no label overlaps`;
        }
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
    // a deep link to an example folded under a card's "more examples" opens that fold and scrolls to it
    const deep = await p.evaluate(() => [...document.querySelectorAll('details.more-ex .sc[id]')].map(e => e.id).filter((_, i, a) => i === 0 || i === a.length - 1));
    for (const id of deep) {
      await go(p, id); await p.waitForTimeout(150);
      const r = await p.evaluate(id => { const t = document.getElementById(id), d = t.closest('details.more-ex'), q = t.getBoundingClientRect();
        return { open: d.open, top: q.top, h: innerHeight, shown: document.documentElement.dataset.view === t.closest('.view').id }; }, id);
      if (!r.open || !r.shown || r.top < -1 || r.top > r.h - 20) bad.push(`${tag}: the deep link #${id} does not open its fold and scroll to it (open ${r.open}, top ${Math.round(r.top)})`);
      else deeps++;
    }
    await p.context().close();
  }
}
await b.close();
console.log(bad.length ? bad.join('\n') : `layout: ${n} view checks (375 and 1280, light and dark, folded and open) fit the screen; the event storm scrolls in its frame on a phone; ${storm}; storm boards have no overlaps; ${deeps} deep links into folded examples open and scroll to them`);
process.exit(bad.length ? 1 : 0);
