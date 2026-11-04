<script>
  export let module;
  export let eventModel;
  export let human;

  const blocks = (kind) => Object.entries(eventModel[kind] || {});
  const refsOf = (block) => block["grain/allium"] || [];
  const linked = (kind, rule) => blocks(kind).filter(([, block]) =>
    refsOf(block).some((ref) => String(ref.spec || "").endsWith(module.file) && ref.name === rule.name));
  const commandName = (rule) => (String(rule.when || "").match(/^(\w+)\(/) || [])[1] || rule.name;
  const actorFor = (rule) => {
    const command = commandName(rule);
    return module.surfaces.find((surface) => (surface.provides || []).includes(command))?.facing
      || (/Operator|Approve|Preview|Submit|Prepare/.test(rule.name) ? "Operator" : "ORC");
  };
  const eventsFor = (rule) => linked("events", rule);
  const aggregate = module.entities.find((entity) => !entity.external)?.name || human(module.id);
</script>

<div class="storm-legend">
  <span><i class="key-command"></i>command</span><span><i class="key-aggregate"></i>boundary</span>
  <span><i class="key-event"></i>event or fact</span><span><i class="key-refusal"></i>requires / refusal</span>
</div>
<div class="storm-wrap">
  <div class="storm">
    {#each module.rules as rule}
      {@const command = linked("commands", rule)[0]}
      {@const events = eventsFor(rule)}
      <div class="cluster">
        <div class="sticky actor">{human(actorFor(rule))}</div>
        <div class="sticky aggregate">{aggregate}</div>
        <div class="sticky command">{human(command?.[0] || commandName(rule))}<small>{rule.name}</small></div>
        <div class="facts">
          {#if events.length}
            {#each events as [key, block]}<div class="sticky event">{human(key)}<small>{block.description || ""}</small></div>{/each}
          {:else}
            {#each (rule.ensures.length ? rule.ensures.slice(0, 3) : ["No durable fact declared here"]) as fact}<div class="sticky event state">{fact}</div>{/each}
          {/if}
        </div>
        <div class="refusals">
          {#each rule.requires as requirement, index}
            <a class="sticky refusal" href="#rule/{module.id}/{rule.name}"><b>Refusal {index + 1}</b>{requirement}</a>
          {/each}
        </div>
      </div>
    {/each}
  </div>
</div>
