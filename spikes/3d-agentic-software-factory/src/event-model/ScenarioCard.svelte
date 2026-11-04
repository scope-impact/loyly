<script>
  export let scenario;
  const splitStep = (step) => {
    const [verb, ...words] = step.split(" ");
    return [verb, words.join(" ")];
  };
</script>

<article class="scenario">
  <header><h3>{scenario.title}</h3><span class="status {scenario.status}">{scenario.status}</span></header>
  <div class="rule-name">{scenario.item ? `@item-${scenario.item} · ` : ""}{scenario.rule || ""}</div>
  {#each scenario.steps as step}
    {@const pair = splitStep(step)}
    <div class="gwt"><b>{pair[0]}</b><span>{pair[1]}</span></div>
  {/each}
</article>
