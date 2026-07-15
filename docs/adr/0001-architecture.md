# ADR-0001: FootwearAdvisor ⊣ Footwear Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1520` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1520` publishes an OSS blueprint for footwear-plant
**operations coordination** (production-batch cutting/lasting/
assembly output and quality-grade/defect-rate data logging, cutting/
sewing/molding-equipment maintenance scheduling, materials-defect/
labor-safety/labeling-compliance concern flagging, and outbound
footwear shipment coordination). Like every actor in this fleet, the
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor +
Phase 0->3 rollout pattern established across the cloud-itonami
fleet.

The closest domain analog is `cloud-itonami-isic-1610` (Sawmilling and
planing of wood): both are back-office plant-operations-coordination
actors with real physical-safety-relevant equipment (saws/planers/
kilns vs. cutting presses/sewing machines/sole-molding presses),
production-batch tracking with quality/grade data, and equipment
maintenance scheduling with a permanent block on directly operating
that equipment. This build mirrors 1610's module shape (advisor ⊣
governor ⊣ phase ⊣ store, four ops, `MemStore`-only backend) closely,
substituting footwear-specific ground truth: `quality-grade`/
`defect-rate-percent` in place of `grade`/`moisture-content-percent`,
`volume-pairs` in place of `volume-board-ft`, and a permanent
`:direct-operate?` block in place of 1610's `:finalize?` (kiln-
schedule) block -- both are a proposal-level field that, if set true,
attempts to bypass "propose/schedule a DRAFT" and reach actual
equipment operation.

This vertical has NO pre-existing `kotoba-lang/footwear`-style
capability library to wrap (verified: no such repo exists). This
build therefore uses self-contained domain logic -- pure functions in
`footwearops.registry` (equipment/batch verification, shipment-volume
recompute, quality-grade validation, defect-rate plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-1610`'s `sawmilling.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:footwear-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "footwear-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

Regulatory context (informational, not enforced in code): footwear
manufacturing is subject to materials-labeling regimes in multiple
jurisdictions -- e.g. EU Directive 94/11/EC (labelling of the
materials used in the main components of footwear sold to consumers),
the US Consumer Product Safety Act / FTC textile-and-footwear
labelling guidance, and Japan's 家庭用品品質表示法 (Household Goods
Quality Labeling Act) covering footwear. `:flag-quality-concern`'s
`:labeling-compliance` concern-type exists to surface exactly this
class of issue to a human for review -- this actor does not itself
adjudicate compliance.

## Decision

### Decision 1: Self-contained domain logic (no external footwear capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
footwear vertical has NO pre-existing capability library to wrap.
The equipment/batch-verification / shipment-volume / quality-grade /
defect-rate validation functions live as pure functions in
`footwearops.registry` and are re-verified independently by
`footwearops.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1610`'s `sawmilling.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of footwear plant
operations. It does NOT:
- Control cutting presses, sewing machines, lasting machines, or sole-molding/injection equipment directly
- Make plant-safety, labor-safety, or labeling-compliance decisions (exclusive to the human plant supervisor)
- Directly operate cutting/lasting/assembly-line equipment under any proposal

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: footwear manufacturing carries real
physical-safety and labor-standards dimensions (cutting-press/die
injury risk, sewing-machine needle injury, sole-molding/injection
thermal and chemical-fume hazard, repetitive-strain labor conditions,
materials-defect and labeling-compliance risk to consumers).
Quality-concern flagging NEVER auto-commits. All quality concerns
escalate immediately to human review.

### Decision 3: Quality-concern escalation — always human sign-off

`:flag-quality-concern` (materials-defect, labor-safety, or
labeling-compliance concern) ALWAYS escalates, never auto-commits.
This is not a "low-stakes proposal" — it is a circuit-breaker that
must reach human authority, deliberately broader in scope than a
single-axis safety flag (mirroring how this vertical's real-world
compliance surface spans product quality, worker safety, and consumer
labeling simultaneously).

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Unlike a single-entity-gated vertical, this vertical has TWO entity
kinds each gating a different op: `:schedule-maintenance`
independently verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "factory/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date volume plus
the proposal's own claimed volume would exceed the batch's own
recorded production volume -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`footwearops.governor`, mirroring `cloud-itonami-isic-1610`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Factory/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's volume must independently recompute within the batch's own logged production volume
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct cutting/lasting/assembly-line-equipment control (`:direct-operate? true`) is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-quality-concern`/`:coordinate-shipment` only

## Consequences

(+) Footwear plant operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation. Quality concerns are a
circuit-breaker, not a threshold.

(+) Safety-critical and compliance discipline is explicit:
quality-concern flagging cannot be rate-limited, suppressed, or
auto-decided by phase gate. Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation remains human-controlled via
external channels.

(-) No integration with real factory-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-1520`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-volume-exceeded, direct-operate-
  blocked, already-scheduled, invalid-grade, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
