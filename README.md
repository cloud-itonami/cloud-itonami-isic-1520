# cloud-itonami-isic-1520: Manufacture of footwear

Open Business Blueprint for **ISIC Rev.5 1520**: manufacture of footwear — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office footwear-plant **operations**: production-batch data logging (cutting/lasting/assembly output and quality grade/defect-rate), cutting/sewing/molding-equipment maintenance scheduling, materials-defect/labor-safety/labeling-compliance concern flagging, and outbound footwear shipment coordination.

This repository designs a forkable OSS business for footwear-plant
operations: run by a qualified operator so a footwear factory keeps its
own operating records instead of renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — cutting/lasting/assembly batch and output-quality (grade/defect-rate) data logging (administrative, not an operational decision)
- `:schedule-maintenance` — cutting/sewing/molding-equipment maintenance scheduling proposal
- `:flag-quality-concern` — surface a materials-defect/labor-safety/labeling-compliance concern (always escalates)
- `:coordinate-shipment` — outbound footwear shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY** (cutting presses, sewing machines, sole-molding/injection equipment; materials-handling and labor-safety hazards):

- Does NOT control cutting, sewing, lasting, or molding equipment directly
- Does NOT make plant-safety, labor-safety, or labeling-compliance decisions (that's the plant supervisor's exclusive human authority)
- Does NOT directly operate cutting/lasting/assembly-line equipment under any proposal (permanently blocked, see Architecture)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Quality-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`footwearops.operation/build`, a langgraph-clj StateGraph):
1. **`footwearops.advisor`** (sealed intelligence node, `FootwearAdvisor`): proposes decisions only, never commits
2. **`footwearops.governor`** (independent, `Footwear Plant Operations Governor`): validates against domain rules, re-derived from `footwearops.registry`'s pure functions and `footwearops.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Factory/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct cutting/lasting/assembly-line-equipment control)
     - Directly operating cutting/lasting/assembly-line equipment (`:direct-operate? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped volume past its own logged production volume (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:quality-grade` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-quality-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`footwearops.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-quality-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`footwearops.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
