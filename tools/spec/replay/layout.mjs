// Layout check of a replay page (Playwright, the installed Chrome, as tools/spec/views/layout.mjs):
// at a phone (375) and a desktop (1280) width, light and dark, the page does not scroll sideways,
// neither as built nor with a sticky's payload open; the Step button walks every sticky to the
// end; and the replay plays through to its last event, with no JavaScript error. Exit 1 on any finding.
//   node tools/spec/replay/layout.mjs <replay.html> [--shots <dir>]
// Uses tools/spec/views' playwright-core (npm install there).
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const { chromium } = createRequire(new URL('../views/package.json', import.meta.url))('playwright-core');
const args = process.argv.slice(2), shots = args.includes('--shots') ? args[args.indexOf('--shots') + 1] : null;
const file = args.find(a => a.endsWith('.html'));
const b = await chromium.launch({ channel: 'chrome' });
const bad = []; let checks = 0;
const wider = p => p.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
for (const width of [375, 1280]) for (const scheme of ['light', 'dark']) {
  const tag = `${width} ${scheme}`, errs = [];
  const p = await (await b.newContext({ viewport: { width, height: 900 }, colorScheme: scheme })).newPage();
  p.on('pageerror', e => errs.push(String(e.message || e)));
  await p.goto(pathToFileURL(file).href); await p.waitForTimeout(200);
  let w = await wider(p); if (w > 1) bad.push(`${tag}: the page is ${w}px wider than the screen`); checks++;
  if (shots && scheme === 'light') await p.screenshot({ path: `${shots}/replay-${width}.png`, fullPage: false });
  if (shots && scheme === 'dark') await p.screenshot({ path: `${shots}/replay-${width}-dark.png`, fullPage: false });
  // the largest sticky's payload, open
  const big = await p.evaluate(() => { const D = JSON.parse(document.getElementById('data').textContent); let k = 0; D.items.forEach((it, i) => { if (it.members.length > D.items[k].members.length) k = i; }); return k; });
  await p.click(`.item[data-item="${big}"]`); await p.waitForTimeout(100);
  await p.evaluate(() => document.querySelectorAll('#detail details').forEach(d => { d.open = true; }));
  w = await wider(p); if (w > 1) bad.push(`${tag}: with a payload open the page is ${w}px wider than the screen`); checks++;
  const dlg = await p.evaluate(() => { const r = document.getElementById('detail').getBoundingClientRect(); return { open: document.getElementById('detail').open, right: r.right, left: r.left }; });
  if (!dlg.open) bad.push(`${tag}: clicking a sticky opened no payload`);
  if (dlg.left < 0 || dlg.right > width) bad.push(`${tag}: the payload dialog runs off the screen (${Math.round(dlg.left)}..${Math.round(dlg.right)})`);
  if (shots && width === 375 && scheme === 'light') await p.screenshot({ path: `${shots}/replay-375-payload.png` });
  await p.click('#detail .close');
  // step through every sticky, then play the whole run at the top speed
  const n = await p.evaluate(() => new Set(JSON.parse(document.getElementById('data').textContent).items.map(i => i.at)).size);
  await p.click('#step');   // at the end, Step rewinds to the first event
  let steps = 0;
  while (!(await p.evaluate(() => document.documentElement.hasAttribute('data-replay-end'))) && steps < n + 5) { await p.click('#step'); steps++; }
  if (steps > n) bad.push(`${tag}: Step did not reach the end in ${n} steps`);
  await p.selectOption('#speed', '16');
  await p.click('#play'); await p.waitForTimeout(400);
  if (shots && width === 1280 && scheme === 'light') await p.screenshot({ path: `${shots}/replay-1280-playing.png` });
  if (await p.evaluate(() => document.documentElement.hasAttribute('data-replay-end'))) bad.push(`${tag}: Replay did not rewind`);
  try { await p.waitForFunction(() => document.documentElement.hasAttribute('data-replay-end'), null, { timeout: 90000 }); }
  catch { bad.push(`${tag}: the replay did not reach its last event in 90 s`); }
  w = await wider(p); if (w > 1) bad.push(`${tag}: after the replay the page is ${w}px wider than the screen`); checks++;
  const jsErr = await p.evaluate(() => document.documentElement.getAttribute('data-js-error'));
  if (jsErr) errs.push(jsErr);
  errs.forEach(e => bad.push(`${tag}: JavaScript error: ${e}`));
  await p.waitForTimeout(400);
  if (shots && width === 375 && scheme === 'light') await p.screenshot({ path: `${shots}/replay-375-end.png`, fullPage: true });
  if (shots && width === 1280 && scheme === 'light') await p.screenshot({ path: `${shots}/replay-1280-full.png`, fullPage: true });
  await p.context().close();
}
await b.close();
console.log(bad.length ? bad.join('\n') : `replay layout: ${checks} width checks (375 and 1280, light and dark, as built, with a payload open, after the replay) fit the screen; Step walks every sticky and the replay plays to its last event with no JavaScript error`);
process.exit(bad.length ? 1 : 0);
