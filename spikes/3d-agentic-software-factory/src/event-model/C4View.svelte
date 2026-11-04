<script>
  import { onMount, tick } from "svelte";
  export let diagrams;
  let full = {};

  onMount(async () => {
    const { default: mermaid } = await import("mermaid");
    mermaid.initialize({
      startOnLoad: false, securityLevel: "strict", theme: "base",
      flowchart: { htmlLabels: true, curve: "basis", useMaxWidth: true },
      themeVariables: { fontFamily: "Helvetica Neue, Arial, sans-serif", fontSize: "13px" },
    });
    await tick();
    await mermaid.run({ querySelector: ".c4-view pre.mermaid" });
  });
</script>

<div class="module-head">
  <div class="eyebrow">Maintained structural views · .pi/architecture.md</div>
  <h1>From system boundary to ORC components.</h1>
  <p class="lede">These diagrams come directly from the repository architecture source. Supporting deployment and dynamic views remain in that document.</p>
</div>
<div class="c4-view">
  {#each diagrams as diagram, index}
    <section>
      <div class="section-head"><h2>{diagram.title}</h2><p>{index === 0 ? "Who uses the harness and which external systems it integrates with." : index === 1 ? "Runnable applications and data stores inside the harness boundary." : "Responsibility boundaries inside the ORC worker."}</p></div>
      <div class:full={full[index]} class="diagram"><button on:click={() => full[index] = !full[index]}>{full[index] ? "Fit to width" : "Actual size"}</button><pre class="mermaid">{diagram.source}</pre></div>
    </section>
  {/each}
</div>
