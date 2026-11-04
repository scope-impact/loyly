<script>
  import { onMount, tick } from "svelte";
  import model from "./model.json";
  import EventStorm from "./EventStorm.svelte";
  import ScenarioCard from "./ScenarioCard.svelte";
  import C4View from "./C4View.svelte";

  const sourceBase = "https://github.com/scope-impact/loyly/blob/spike/3d-agentic-software-factory/";
  const modules = Object.fromEntries(model.modules.map((entry) => [entry.id, entry]));
  const human = (value) => String(value ?? "").split("/").pop().replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[-_]/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
  const blocks = (kind) => Object.entries(model.eventModel[kind] || {});
  const scenariosFor = (id) => model.scenarios.filter((scenario) => scenario.module === id);
  const totals = {
    rules: model.modules.reduce((sum, entry) => sum + entry.rules.length, 0),
    e2e: model.scenarios.filter((scenario) => scenario.status === "e2e").length,
    skip: model.scenarios.filter((scenario) => scenario.status === "skip").length,
  };
  let route = "home";
  let reviewFilter = "all";

  onMount(() => {
    const update = () => { route = (location.hash || "#home").slice(1); window.scrollTo(0, 0); };
    update(); addEventListener("hashchange", update);
    return () => removeEventListener("hashchange", update);
  });
  $: routeParts = route.split("/");
  $: moduleId = routeParts[0] === "module" || routeParts[0] === "rule" ? routeParts[1] : null;
  $: currentModule = moduleId ? modules[moduleId] : null;
  $: nav = route === "topology" ? "topology" : route === "c4" ? "c4" : route === "review" ? "review" : "home";
  $: if (routeParts[0] === "rule" && currentModule) tick().then(() => {
    const detail = document.getElementById(`rule-${routeParts[2]}`);
    if (detail) { detail.open = true; detail.scrollIntoView({ block: "start" }); }
  });
</script>

<div class="shell">
  <header class="topbar">
    <a class="brand" href="#home"><span class="brand-mark" aria-hidden="true"></span><span><strong>Pi Harness</strong><small>Generated event model</small></span></a>
    <nav class="nav" aria-label="Primary"><a class:on={nav === "home"} href="#home">Model</a><a class:on={nav === "topology"} href="#topology">Grain</a><a class:on={nav === "c4"} href="#c4">C4</a><a class:on={nav === "review"} href="#review">Review</a></nav>
  </header>
  <div class="breadcrumbs"><a href="#home">Pi Harness</a>{#if currentModule}<i>›</i><a href="#module/{currentModule.id}">{human(currentModule.id)}</a>{:else if nav !== "home"}<i>›</i><span>{human(nav)}</span>{/if}</div>
  <main>
    {#if currentModule}
      <div class="module-head"><div class="eyebrow">Behavior module · {currentModule.file}</div><h1>{human(currentModule.id)}</h1><p class="lede">{currentModule.description}</p><div class="module-meta"><span class="chip">{currentModule.entities.length} entities</span><span class="chip">{currentModule.rules.length} rules</span><span class="chip">{currentModule.invariants.length} invariants</span><span class="chip">{currentModule.surfaces.length} surfaces</span><span class="chip">{scenariosFor(currentModule.id).length} scenarios</span></div></div>
      <section><div class="section-head"><h2>Event storm</h2><p>Commands, consistency boundaries, recorded events or state facts, and explicit refusals. The board follows rule order from the authoritative Allium source.</p></div><EventStorm module={currentModule} eventModel={model.eventModel} {human}/></section>
      <section><div class="section-head"><h2>Rules</h2><p>The exact When / Only if / Then structure, with authoritative source available in place.</p></div><div class="rule-list">{#each currentModule.rules as rule, index}<details class="rule-card" id="rule-{rule.name}" open={index === 0}><summary><b>{rule.name}</b><span>{rule.when}</span><em>{rule.requires.length} refusals · {rule.ensures.length} effects</em></summary><div class="rule-body"><div class="clause"><b>WHEN</b><code>{rule.when}</code></div>{#each rule.requires as item}<div class="clause"><b>ONLY IF</b><code>{item}</code></div>{/each}{#each rule.ensures as item}<div class="clause"><b>THEN</b><code>{item}</code></div>{/each}<details><summary>Allium source</summary><pre class="source">{rule.source}</pre></details></div></details>{/each}</div></section>
      <section><div class="section-head"><h2>Acceptance examples</h2><p>Executable and explicitly skipped scenarios stay visible together; the denominator is never hidden to make progress look better.</p></div><div class="scenario-grid">{#each scenariosFor(currentModule.id) as scenario}<ScenarioCard {scenario}/>{:else}<div class="empty">No matching feature file.</div>{/each}</div></section>
    {:else if route === "topology"}
      <div class="module-head"><div class="eyebrow">Durable service area · regeneration_event_model.clj</div><h1>Commands become facts, then projections.</h1><p class="lede">The Pi harness shares Grain infrastructure with ORC without duplicating ORC execution events or Löyly product events.</p></div>
      <section><div class="topology-grid">{#each [["commands","Commands",""],["events","Events","events"],["read-models","Read models","read"],["queries","Queries","read"],["todo-processors","Processors","policy"],["screens","Screens","policy"]] as [kind,title,klass]}<div class="topology-column {klass}"><h3>{title} <small>{blocks(kind).length}</small></h3>{#each blocks(kind) as [key,block]}<span class="block" title={block.description || ""}>{human(key)}</span>{/each}</div>{/each}</div></section>
      <section><div class="section-head"><h2>Named flows</h2><p>Directed persistence and projection topology. It does not replace ORC workflow authority.</p></div><div class="flow-list">{#each blocks("flows") as [key,flow]}<article class="flow"><h3>{human(key)}</h3><p>{flow.description}</p><div class="flow-steps">{#each flow.steps || [] as step}<span class="flow-node"><b>{step.from?.[0]}</b><br>{human(step.from?.[1])}</span><span class="flow-step-arrow">›</span><span class="flow-node"><b>{step.to?.[0]}</b><br>{human(step.to?.[1])}</span>{/each}</div></article>{/each}</div></section>
    {:else if route === "c4"}
      <C4View diagrams={model.c4}/>
    {:else if route === "review"}
      <div class="module-head"><div class="eyebrow">Complete denominator · target versus executable reality</div><h1>Progress without hidden gaps.</h1><p class="lede">Active E2E, explicit skips, and architecture conformance notes are presented together. A skipped or unreached item is not silently converted into success.</p><div class="stats"><div class="stat"><b>{model.modules.length}</b><span>behavior modules</span></div><div class="stat"><b>{totals.rules}</b><span>Allium rules</span></div><div class="stat"><b>{model.scenarios.length}</b><span>acceptance scenarios</span></div><div class="stat"><b>{totals.e2e}</b><span>active E2E</span></div><div class="stat"><b>{totals.skip}</b><span>explicit gaps</span></div></div></div>
      <section><div class="section-head"><h2>Acceptance inventory</h2><p>Filtering changes the view, never the denominator.</p></div><div class="review-tools">{#each [["all","All"],["e2e","Active E2E"],["skip","Explicit skip"]] as [value,label]}<button class:on={reviewFilter === value} on:click={() => reviewFilter = value}>{label}</button>{/each}</div><div class="table-scroll"><table class="review-table"><thead><tr><th>Item</th><th>Module</th><th>Scenario</th><th>State</th></tr></thead><tbody>{#each model.scenarios.filter((scenario) => reviewFilter === "all" || scenario.status === reviewFilter) as scenario}<tr><td>{scenario.item || "—"}</td><td><a href="#module/{scenario.module}">{human(scenario.module)}</a></td><td>{scenario.title}</td><td><span class="status {scenario.status}">{scenario.status}</span></td></tr>{/each}</tbody></table></div></section>
      <section><div class="section-head"><h2>Architecture conformance</h2><p>Current implementation annotations extracted from Appendix A of the maintained architecture.</p></div><div class="table-scroll"><table class="review-table"><thead><tr><th>Item</th><th>Status</th><th>Evidence or gap</th></tr></thead><tbody>{#each model.conformance as item}<tr><td>{item.item}</td><td>{item.status}</td><td>{item.note}</td></tr>{/each}</tbody></table></div></section>
    {:else}
      <div class="hero"><div><div class="eyebrow">Phoenix architecture · executable behavioral model</div><h1>The harness is a service area, not a script.</h1><p class="lede">Explore who may decide, what ORC owns, how independent evidence is produced, and where implementation still falls short of the target contract.</p><div class="stats"><div class="stat"><b>{model.modules.length}</b><span>behavior modules</span></div><div class="stat"><b>{totals.rules}</b><span>Allium rules</span></div><div class="stat"><b>{model.scenarios.length}</b><span>acceptance scenarios</span></div><div class="stat"><b>{totals.e2e}</b><span>active E2E</span></div><div class="stat"><b>{totals.skip}</b><span>explicit gaps</span></div></div></div><aside class="source-note"><strong>Authority stays in the model</strong><p>This site is rebuilt from committed specifications. It explains them; it does not replace them.</p><a href="{sourceBase}model/pi-harness/README.md">Read the canonical contract</a></aside></div>
      <section><div class="section-head"><h2>Two explicit routes</h2><p>Candidate history never selects the operation. The approved request names Fresh Slice Generation or Measured Slice Regeneration before ORC begins.</p></div><div class="model-boundary"><span class="boundary-label">Approved slice route → ORC</span><div class="route-lanes"><div class="route"><b>Fresh Slice Generation Cycle</b><span>No parent Candidate · no accepted product oracle · successful Grade proposes the first oracle</span></div><div class="route-arrow">↔</div><div class="route"><b>Measured Slice Regeneration Cycle</b><span>Named measured parent · accepted product oracle · fresh evidence compared with ratified evidence</span></div></div></div></section>
      <section><div class="section-head"><h2>Behavior modules</h2><p>Each module is one Allium file with its rules, invariants, surfaces, Grain links, and matching acceptance examples.</p></div><div class="module-grid">{#each model.modules as entry}<a class="module-card" href="#module/{entry.id}"><div><span class="count">{entry.rules.length} rules · {scenariosFor(entry.id).length} scenarios</span><h3>{human(entry.id)}</h3><p>{entry.description || "Behavioral model and acceptance projection."}</p></div><span class="open">Open EventStorming board →</span></a>{/each}</div></section>
    {/if}
  </main>
  <footer class="foot"><strong>Generated, not hand-authored.</strong> Behavior comes from <code>model/pi-harness/*.allium</code>; examples from Gherkin; durable topology from <code>regeneration_event_model.clj</code>; architecture from <code>.pi/architecture.md</code>.</footer>
</div>
