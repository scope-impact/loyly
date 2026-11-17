// The cycle's document: where the loop is. Its own module because the loop and the Slack
// listener both change it, each inside its own commits.

import { defineDoc } from "@earendil-works/pi-durable";

/** Bump when the shape of the document changes. */
const CYCLE_VERSION = 1;

/** The cycle's position. Everything the loop needs to continue after a crash is in here. */
export const initialCycle = () => ({
	phase: "new", // new -> plan -> implement [-> awaiting-approval] -> done | exhausted
	round: 0, // the last round that finished
	tdd: "GREEN", // GREEN: write the next failing test. RED: make it pass.
	planConversation: null,
	buildConversation: null,
	plan: null, // the planning answer, handed to every implementation round
	pending: null, // { round, kind, stage: "opened" | "reset" | "submitted", requestId }
	ratified: null, // { at, summary, output } once harness/verify.sh passed under claim_done
	findings: [], // the latest review's answer (or a rejection's reason), handed to the next build rounds
	humanNotes: [], // what people said in Slack while no round was running; the next round gets them
	approval: null, // { by, at } once a person approved a ratified DONE
	history: [],
});
export const Cycle = defineDoc({
	kind: "loyly.cycle",
	version: CYCLE_VERSION,
	scope: "session",
	initial: initialCycle,
	checkpointWhen: () => true,
});

