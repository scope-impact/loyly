#!/usr/bin/env python3
"""Build a public playback of cycle-25 grading from the filed OTLP trace.

Only explicitly selected trace fields enter the page. The recorded evidence files are
read-only inputs; this is a visualization, not a new score or a runner-store replay.
"""

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = ROOT / "harness/v2/cycles/06/25"
OUTPUT = ROOT / "tools/spec/replay/cycle-25-grading.html"


def read(name):
    return json.loads((EVIDENCE / name).read_text())


def attributes(record):
    return {a["key"]: next(iter(a["value"].values())) for a in record.get("attributes", [])}


def main():
    trace = read("profile.traces.otlp.json")
    profile = read("profile.json")
    scores = read("scores.json")
    spans = [
        span
        for resource in trace["resourceSpans"]
        for scope in resource["scopeSpans"]
        for span in scope["spans"]
    ]
    grade_spans = [span for span in spans if span["name"] == "loyly.grade.item"]
    score_by_id = {item["id"]: item for item in scores["items"]}
    profile_by_id = {case["id"]: case for case in profile["cases"]}
    assert len(grade_spans) == len(score_by_id) == len(profile_by_id) == 61
    assert profile["status"] == "failed" and profile["verdicts"]["pass_count"] == 61
    base_time = min(int(span["startTimeUnixNano"]) for span in spans)
    cases = []
    events = []
    allowed = {
        "loyly.profile.phase", "loyly.vm.qemu.count", "loyly.process.count",
        "loyly.grain.events", "loyly.orc.traces", "loyly.orc.nodes",
        "loyly.judge.scores", "loyly.orc.sheet", "loyly.orc.status",
        "loyly.orc.duration.ms", "loyly.orc.node", "loyly.orc.executions",
        "loyly.orc.total_tokens", "loyly.jvm.command", "loyly.jvm.status",
    }
    for span in grade_spans:
        attrs = attributes(span)
        item_id = attrs["loyly.item.id"]
        score = score_by_id[item_id]
        case = profile_by_id[item_id]
        assert score["verdict"] == "pass" and case["status"] == "clean"
        cases.append({
            "id": item_id, "name": score["name"], "verdict": score["verdict"],
            "profile": case["status"], "model": case["model_requests"],
            "qemu": case["peak_observed_qemu"], "grain": case["grain_profiles"],
            "elapsed": case["elapsed_ms"], "events": len(span.get("events", [])),
        })
        for index, event in enumerate(span.get("events", [])):
            event_attrs = attributes(event)
            if event["name"] == "loyly.profile.mark":
                mark_name = event_attrs.get("loyly.mark.name")
                if mark_name not in {"run-id", "trace-id", "model-requests"}:
                    continue
                detail = {"mark": mark_name}
                if mark_name == "model-requests":
                    detail["count"] = int(event_attrs["loyly.mark.value"])
            else:
                detail = {key.removeprefix("loyly."): value for key, value in event_attrs.items() if key in allowed}
            events.append({
                "t": (int(event["timeUnixNano"]) - base_time) // 1_000_000,
                "id": item_id, "kind": event["name"].removeprefix("loyly."),
                "detail": detail, "order": index,
            })
    assert len(events) >= 3000
    events.sort(key=lambda event: (event["t"], event["id"], event["order"]))
    for event in events:
        del event["order"]
    cases.sort(key=lambda case: case["id"])
    data = {"cases": cases, "events": events, "status": profile["status"],
            "problem": profile["problems"][0], "trace": profile["otel_trace_id"]}
    data_json = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    data_json = data_json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    page = TEMPLATE.replace("DATA_JSON", data_json)
    OUTPUT.write_text(page)
    print(f"{OUTPUT}: {len(cases)} cases, {len(events)} recorded trace events")


TEMPLATE = '''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cycle 25 grading playback | Löyly</title>
<meta name="description" content="Recorded OTLP timeline of the 61 cycle-25 runner grading cases.">
<style>
:root{font-family:system-ui,-apple-system,Segoe UI,sans-serif;color-scheme:light dark;--bg:#f7f5f0;--card:#fff;--ink:#22211e;--muted:#625e55;--line:#d7d2c8;--green:#216335;--amber:#805d17}
@media(prefers-color-scheme:dark){:root{--bg:#171713;--card:#23231e;--ink:#eeece5;--muted:#bbb5a8;--line:#49463e;--green:#9cdbab;--amber:#e2bd70}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink)}main{max-width:1100px;margin:auto;padding:24px 20px 70px}a{color:inherit;text-underline-offset:3px}nav{font-size:.9rem;color:var(--muted)}nav a{margin-right:16px}h1{font-size:clamp(2rem,5vw,3.2rem);letter-spacing:-.04em;line-height:1.1;margin:38px 0 10px}h2{font-size:1.25rem;margin:30px 0 10px}.muted{color:var(--muted)}.lede{font-size:1.1rem;max-width:78ch}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:22px 0}.card,.panel,.note{background:var(--card);border:1px solid var(--line);border-radius:11px;padding:16px}.card b{display:block;font-size:1.35rem}.card span{font-size:.83rem;color:var(--muted)}.good{color:var(--green)}.warn{color:var(--amber)}.note{border-left:4px solid var(--amber)}button,select,input{font:inherit}button,select{background:var(--card);color:var(--ink);border:1px solid var(--line);border-radius:7px;padding:7px 12px;cursor:pointer}button:focus-visible,select:focus-visible,input:focus-visible{outline:3px solid var(--green)}.controls{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.controls input{flex:1;min-width:180px}.columns{display:grid;grid-template-columns:minmax(240px,1fr) minmax(0,2fr);gap:14px;margin-top:16px}.list{max-height:540px;overflow:auto;display:grid;align-content:start;gap:4px}.case{width:100%;text-align:left;display:flex;justify-content:space-between;gap:8px}.case.active{border-color:var(--green);background:color-mix(in srgb,var(--green) 11%,var(--card))}.case small{color:var(--muted)}.event{border-bottom:1px solid var(--line);padding:8px 0;font-size:.9rem}.event b{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.event small{color:var(--muted)}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:.8rem;margin:5px 0 0}#now{min-height:55px}.eyebrow{text-transform:uppercase;letter-spacing:.12em;font-size:.75rem;font-weight:700;color:var(--muted)}@media(max-width:700px){.cards{grid-template-columns:repeat(2,1fr)}.columns{grid-template-columns:1fr}.list{max-height:240px}}
</style></head><body><main>
<nav><a href="../index.html">Event model</a><a href="../eval/cycle-runner.html">Cycle 25 evaluation</a><a href="loyly-run-03.html">Historical run-03 replay</a></nav>
<div class="eyebrow" style="margin-top:34px">Spec v2 · slice 06 · recorded grading trace</div>
<h1>Cycle 25 grading playback</h1>
<p class="lede">Step through the recorded OpenTelemetry events from the independent grading of the accepted runner. This is the grading session for 61 runner behaviors. The run-03 page replays a separate Löyly product build.</p>
<div class="cards"><div class="card"><b class="good">61 / 61</b><span>behavior verdicts passed</span></div><div class="card"><b id="event-count">3,023</b><span>recorded trace events</span></div><div class="card"><b>61</b><span>canonical item profiles clean</span></div><div class="card"><b class="warn">Exception</b><span>filed profile status: failed</span></div></div>
<div class="note"><strong>Profile exception remains visible.</strong> Eight separately captured retry names are outside the checklist, so the filed profile status is <code>failed</code>. Their raw boundaries and cleanup were audited and the operator explicitly accepted the exception. This playback does not change any score or profile.</div>
<h2>Replay the grading trace</h2><div class="panel"><div class="controls"><button id="play" type="button">Play</button><button id="reset" type="button">Start</button><label for="speed">Speed</label><select id="speed"><option value="1">1×</option><option value="4" selected>4×</option><option value="12">12×</option></select><input id="position" type="range" min="0" max="0" value="0" aria-label="Trace event position"><output id="progress">0 / 0</output></div><p id="now" aria-live="polite"></p></div>
<div class="columns"><section class="panel"><h2>Items</h2><p class="muted">Choose an item to jump to its first recorded event.</p><div id="cases" class="list"></div></section><section class="panel"><h2>Recorded events</h2><p class="muted">Times are offsets from the start of the grading trace. Events with the same timestamp retain their filed order.</p><div id="events" class="list"></div></section></div>
<p class="muted">Source: <a href="https://github.com/ragavsathish/loyly/blob/spec-v2/harness/v2/cycles/06/25/profile.traces.otlp.json">filed OTLP trace</a>, <a href="https://github.com/ragavsathish/loyly/blob/spec-v2/harness/v2/cycles/06/25/scores.json">score</a>, and <a href="https://github.com/ragavsathish/loyly/blob/spec-v2/harness/v2/cycles/06/25/profile-retry-audit.md">retry audit</a>. Only selected diagnostic fields are displayed; raw IDs, paths and prompts are omitted.</p>
</main><script id="trace-data" type="application/json">DATA_JSON</script><script>
const D=JSON.parse(document.getElementById('trace-data').textContent);
const E=D.events,C=D.cases,byId=new Map(C.map(c=>[c.id,c]));
const $=id=>document.getElementById(id), range=$('position'),eventList=$('events'),caseList=$('cases');
range.max=String(E.length);$('event-count').textContent=E.length.toLocaleString();
let pos=0,timer=null;const first=new Map();E.forEach((e,i)=>{if(!first.has(e.id))first.set(e.id,i)});
const caseButtons=new Map();for(const c of C){const b=document.createElement('button');b.type='button';b.className='case';const title=document.createElement('span');title.textContent=c.id+' · '+c.name;const meta=document.createElement('small');meta.textContent=c.verdict+' · '+c.events+' events';b.append(title,meta);b.title=c.name;b.onclick=()=>{stop();show(first.get(c.id)+1)};caseList.append(b);caseButtons.set(c.id,b)}
function fmt(ms){const s=Math.floor(ms/1000);return '+'+Math.floor(s/60)+'m '+String(s%60).padStart(2,'0')+'s'}
function stop(){if(timer){clearInterval(timer);timer=null}$('play').textContent='Play'}
function show(n){pos=Math.max(0,Math.min(E.length,n));range.value=String(pos);$('progress').textContent=pos+' / '+E.length;eventList.replaceChildren();const current=pos?E[pos-1]:null;const visible=E.slice(Math.max(0,pos-16),pos).reverse();for(const e of visible){const row=document.createElement('div');row.className='event';const head=document.createElement('div');const bold=document.createElement('b');bold.textContent=e.id+' '+e.kind;const time=document.createElement('small');time.textContent='  '+fmt(e.t);head.append(bold,time);const detail=document.createElement('pre');detail.textContent=JSON.stringify(e.detail);row.append(head,detail);eventList.append(row)}$('now').textContent=current?`${current.id}: ${byId.get(current.id).name} · ${current.kind} at ${fmt(current.t)}`:'At the start of the recorded grading trace';for(const [id,b] of caseButtons)b.classList.toggle('active',!!current&&id===current.id);if(pos===E.length)stop()}
$('play').onclick=()=>{if(timer){stop();return}if(pos===E.length)show(0);$('play').textContent='Pause';timer=setInterval(()=>show(pos+Number($('speed').value)),80)};
$('reset').onclick=()=>{stop();show(0)};range.oninput=()=>{stop();show(Number(range.value))};show(0);
</script></body></html>
'''


if __name__ == "__main__":
    main()
