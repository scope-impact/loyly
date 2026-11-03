# Automotive assembly layout research

Primary-source notes for making the 3D agentic-software factory read like a real automobile plant. The recommendations below are layout inferences, not claims that every plant has the same floor plan.

## What real production flows establish

- The canonical vehicle flow is **press/stamping → body shop/welding → paint → assembly → inspection/testing → distribution**. Toyota presents exactly this five-step sequence, with inspection and shipping as the final step; BMW separately describes component production, vehicle structure, paint, assembly, then distribution. ([Toyota virtual plant tour](https://global.toyota/en/company/plant-tours/), [BMW production overview](https://www.bmwgroup-werke.com/content/grpw/websites/bmwgroup_com/en/company/production.html))
- The first three shops transform the same increasingly complete shell: coils/blanks become panels in the press shop; hundreds of panels are joined into a body shell in the body shop; the bare shell is cleaned, dipped/coated, painted, and checked in the paint shop. Body construction is highly robotic, while paint has its own staged treatment and inspection sequence. ([BMW Leipzig production stages](https://www.bmwgroup-werke.com/leipzig/en.html), [Toyota painting process](https://global.toyota/jp/company/plant-tours/painting/))
- General assembly is the long, workstation-heavy part of the plant. BMW Regensburg reports an approximately **5.5 km assembly line**, split into body assembly, final assembly, and finish. The drivetrain/chassis is joined to the body at the central “marriage”; final tests follow. Components must arrive at the correct place, time, and sequence via an internal logistics system. ([BMW Regensburg production](https://www.bmwgroup-werke.com/regensburg/en.html))
- Mixed models need not have separate main lines. BMW Leipzig produces four models and three drive options on one line; Mercedes Factory 56 likewise supports different body and drive types in one hall, using modular stations and automated guided vehicles in concentrated “TecLines.” ([BMW Leipzig plant tours](https://www.bmwgroup-werke.com/leipzig/en.html), [Mercedes-Benz Factory 56](https://group.mercedes-benz.com/unternehmen/produktion/produktionsnetzwerk/eroeffnung-factory-56.html))
- Quality gates belong throughout the flow, not only at the exit. BMW describes dimensional/surface checks after production stages and checks between paint coats. Mercedes tracks every vehicle and exposes live quality status so nonconformities can be handled before end of line. ([BMW Regensburg production](https://www.bmwgroup-werke.com/regensburg/en.html), [Mercedes-Benz MO360](https://media.mercedes-benz.com/en/article/abc9fd0b-edf2-4edd-901a-8bd5a294804a))
- End-of-line is a distinct test/finish zone. BMW sends completed vehicles through a predefined functional test cycle; Toyota's final inspection checks specification, exterior surfaces, lights/instruments, adjustment, and leaks before shipping. ([BMW Regensburg production](https://www.bmwgroup-werke.com/regensburg/en.html), [Toyota inspection and shipping](https://global.toyota/jp/company/plant-tours/inspection-shipping/))
- Rework should be visible but off the happy-path conveyor. Ford explicitly describes leak detection and testing equipment plus a **new repair area** alongside its Trim, Chassis and Final line. Mercedes treats rework as a quality-control loop, while targeting vehicles leaving the line without rectification. ([Ford Silverton plant investment PDF](https://media.ford.com/content/dam/fordmedia/fmea/South-Africa/08%20NOV%2023%20Ford%20SA%20Centenary%20-%20History%20Investment%20Product%20CSR%20overview%20%20-%20Nov%202023%20Final.pdf), [Mercedes-Benz MO360](https://media.mercedes-benz.com/en/article/abc9fd0b-edf2-4edd-901a-8bd5a294804a))
- Passed vehicles leave production for interim finished-vehicle storage, then distribution by truck, rail, or ship. This is a yard/dock destination, not another fabrication station. ([BMW production overview](https://www.bmwgroup-werke.com/content/grpw/websites/bmwgroup_com/en/company/production.html))

## Recommended 3D game footprint

Use a legible one-way **folded line**, not a radial network:

```text
RECEIVING / COILS
        │
        ▼
┌───────────┐   ┌───────────┐   ┌───────────┐
│ STAMPING  │ → │ BODY SHOP │ → │   PAINT   │
└───────────┘   └───────────┘   └─────┬─────┘
                                      │ U-turn / buffer
                                      ▼
┌───────────┐   ┌───────────┐   ┌───────────┐
│ END LINE  │ ← │ MARRIAGE  │ ← │ TRIM / GA │  ← side-fed parts
│ TEST      │   │ + FINAL   │   │ long line │
└─────┬─────┘   └───────────┘   └───────────┘
      │ pass                         ▲ ▲ ▲
      ▼                              │ │ │
 FINISHED YARD                  logistics aisles
      │
      └─ fail → REWORK BAY → retest ─┘
```

Design implications for the spike:

1. Make the **main vehicle route visually dominant**: one continuous conveyor/track, strong direction arrows, sequential station numbering, and no long relationship lines crossing the floor.
2. Fold the route into two or three parallel runs (a U or shallow S) so the entire factory remains visible while preserving strict order. The fold is spatial compression, not a branch in the process.
3. Give major shops different architecture: heavy press hall at receiving; dense robot cells in body; enclosed/clean paint tunnel; long open general-assembly hall; bright inspection tunnel; compact repair bays; outdoor finished-vehicle grid.
4. Expand **general assembly** into several adjacent stations—software equivalent of trim, “marriage,” final assembly, and finish—because real plants devote many workstations and the longest line to this stage.
5. Put supplier/part staging along the outside edge of general assembly, with short perpendicular feeder aisles. Keep those logistics routes subordinate to the vehicle route.
6. Put **test and inspection immediately after final assembly**. A passed product proceeds directly to the finished yard; a failed product diverts into a nearby side-loop rework bay and returns to the same test gate.
7. Render C4 relationships as overhead utility/data gantries or local side routes when they are not the physical product flow. This preserves the actual diagrams without making their network topology dictate the building footprint.
8. Use one mixed-model line for Rust/Java/Rails/Node “cars.” Different models can carry different fixtures, work instructions, or station dwell animations while sharing the same factory path.

## Suggested analogy mapping

| Automobile zone | Agentic software factory role |
|---|---|
| Receiving / stamping | Intake, goal/spec preparation |
| Body shop | Architecture and structural generation |
| Paint shop | Integration, formatting, packaging |
| Trim / general assembly | Implementation by Pi agents/tools |
| Marriage | Bring generated components and runtime/dependencies together |
| End-of-line testing | Deterministic verification and independent graders |
| Rework bay | Diagnosed repair attempt, then retest |
| Finished-vehicle yard | Accepted Löyly candidate/evidence bundle |

The C4 diagram remains the source of truth for which real components exist and communicate; the automotive layout supplies only their physical staging and traversal order in the game world.
