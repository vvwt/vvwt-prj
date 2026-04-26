<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-45.md at cf2ebf6c9b1e6752b7a62413c0b1dab007d58762 2026-04-26 -->
---
id: DEC-45
domain: architecture
level: architectural
title: "DEC-40 Clause C L2.5 examination verdict for the Wave-2 trajectory: L2 (Primary-Adapter-Isolation) stays for E25 + E26 + E27, with explicit per-epic Trigger-(i)/(ii) reservations; Trigger-α-driven re-examination obligation administratively pre-approved for the Wave-2-known context additions"
status: active
created_by: discovery
created_at: 2026-04-26
last_updated_by: discovery
last_updated_at: 2026-04-26
supersedes: null
superseded_by: null
amends: null
tags:
  - architecture
  - spring-modulith
  - hexagonal
  - l2-stays
  - clause-c-verdict
  - wave-2-trajectory
  - application-module
related_to: [DEC-37, DEC-40, DEC-44]
session_brief_ref: discovery-2026-04-26-pfade-wave2-architecture-review
---

# DEC-45 — DEC-40 Clause C L2.5 Verdict: L2 Stays for the Wave-2 Trajectory

## Context

DEC-40 (2026-04-22) introduced two related expansion-trigger mechanisms in its Clause A:

- **Trigger α:** "If the set grows to include ≥5 bounded contexts (excluding `tenant`), Discovery MUST re-examine whether L2.5 (Clause C) should escalate. ... A 5+ context web module has lost meaningful boundary discipline."
- **Trigger β:** ">2 contexts imported by a single controller method" — also forces L2.5 re-examination.

DEC-40 Clause C codifies the L2.5 escalation as the architectural response — introducing a `de.vvwt.tm.application` Modulith module to host cross-context application-services. Trigger conditions for L2.5 escalation:

- **(i)** A story requires a service that reads from AND writes to multiple bounded contexts in a single transactional scope, with event-driven decoupling insufficient.
- **(ii)** A scheduled or asynchronous job spans multiple bounded contexts with ordering or consistency requirements not satisfiable by per-context event handlers.
- **(iii)** Either Trigger α or β fires.

### Empirical state at DEC-45 authoring (2026-04-26)

Trigger α has been firing for the web module since **E24S06** (which added `print` — the 5th bounded context excluding tenant). The re-examination at E24S06 was conducted informally during E24S01 Discovery and produced "binding verdict L2 stays" — documented in `web/package-info.java` Javadoc print-row but **not in any DEC**. This left a governance-trail-gap that DEC-45 closes by formalizing the L2-stays verdict for the Wave-2 trajectory.

E25 (`display`), E26 (`timer`), and possibly E27 (`slotopt-integration`) are the remaining Wave-2 epics that will further extend `web.allowedDependencies`. Per audit (iv) + audit (v):

- E25 adds `display` → 6 bounded contexts (excl. tenant).
- E26 adds `timer` → 7 bounded contexts (excl. tenant).
- E27 likely adds NO REST surface; if status endpoints are introduced at E27 Discovery, possibly 8 bounded contexts.

Each of these additions individually fires Trigger α. Without DEC-45, each E25/E26/E27 Discovery would be required to repeat the L2.5 re-examination ceremonially, producing an L2-stays verdict each time (per audit (iv)/(v) preliminary findings — Trigger (i) NOT satisfied; Trigger (ii) UNVERIFIED for E26 timer WebSocket pattern; Trigger (i)+(ii) NOT satisfied for E25/E27).

### Companion DEC

DEC-44 (authored in this same Discovery session) addresses the parallel governance-trail-gap on **Clause E §Sub-Clause-3** (the structural-escape SHALL for IT annotation that ALSO fired at E24S06). DEC-44 (mechanism: IT-annotation switch to `@SpringBootTest`) and DEC-45 (architectural verdict: L2 stays) together close the DEC-40 expansion-rule "coincidence path" governance-trail-gap.

### Substantive Trigger-(i)/(ii) examination for the Wave-2 trajectory

For each Wave-2 epic, Trigger (i) and Trigger (ii) were evaluated at scope-preview level:

| Epic | Trigger (i) — multi-context read+write in single Tx | Trigger (ii) — multi-context async/scheduled job with ordering |
|---|---|---|
| **E25** (`display`) | NOT satisfied. `DefaultDisplayOverviewService` is a read-only orchestrator over tournament repositories (per audit-(i) of E25 Discovery). No write to other bounded contexts. | NOT satisfied. Display SPA consumes WebSocket events client-side; Java side is REST + MVC primary-adapter only (audit-(iii) of E25 Discovery). |
| **E26** (`timer`) | NOT satisfied. `TimerDataService` reads tournament round-schedule + own audio metadata; no cross-context write (per audit (iv) preliminary). | **UNVERIFIED at scope-preview.** Timer countdown engine + WebSocket sync (E11 lineage) may consume multi-context events (`LAP_ADVANCED` from tournament + match-state from scoring + audio-cue from timer) with ordering implications. **Audit (iv) explicitly RESERVES Trigger-(ii) re-examination for E26 per-epic Discovery before invoking DEC-45 pre-approval.** |
| **E27** (`slotopt-integration`) | NOT satisfied. `SlotOptimizationClient` is a single-context-write integration (writes only to tournament entities via `SlotResultApplicator`); external boundary (TM ↔ dispatcher) is per DEC-11, not a Modulith module boundary. | NOT satisfied. Slot-opt dispatch is request-response to external service, not a multi-context async job. |

### Industry / convention basis for the L2-stays verdict

- **L2.5 introduction without a concrete cross-context application-service** is YAGNI-style overengineering — DEC-40 Clause C explicitly preserves L2.5 as an "evolutionary option with explicit triggers" specifically to avoid premature module-introduction.
- **Spring Modulith reference patterns** (Codecentric, edreyer/modulith, Frankel) document `web` as a single primary-adapter module without an intermediate `application` module for read-orchestrating use cases. DEC-40's L2 Pragmatic-Hexagonal aligns with this corpus.
- **Spring Boot's selective-async pattern** (DEC-37 Clause A) governs concurrent-use-case scaling without requiring a separate application-services module.

---

## Decision

DEC-45 has three clauses.

### D1 — L2-stays verdict for the Wave-2 trajectory

The Trigger-α (Clause A expansion-rule) re-examination obligation that fires at each Wave-2 context addition (E25, E26, E27) is **administratively resolved** with the verdict **L2 (Primary-Adapter-Isolation per DEC-40 Clause A) stays**. No L2.5 escalation. No `de.vvwt.tm.application` module introduction during Wave-2.

**This pre-approval is bounded to the Wave-2 trajectory.** It covers exactly the three already-known Wave-2 context additions to `web.allowedDependencies`: `display` (E25), `timer` (E26), and any web-surface-bearing addition E27 may produce. For Wave-3+ epics or any new context addition beyond, the Trigger-α re-examination obligation is reinstated in full.

### D2 — Per-epic Trigger-(i)/(ii) reservations

For each Wave-2 epic, the L2-stays pre-approval is **conditional** on the substantive Triggers (i) and (ii) of DEC-40 Clause C remaining un-satisfied at the per-epic Discovery round:

- **E25 (`display`):** L2-stays pre-approval **applies unconditionally** based on audit-(i)+(ii)+(iii) findings. No further Trigger-(i)/(ii) examination required at E25 resumption Discovery.
- **E26 (`timer`):** L2-stays pre-approval is **PROVISIONAL**. E26 per-epic Discovery MUST empirically re-examine **Trigger (ii)** for the timer countdown + WebSocket-sync pattern (per audit (iv) explicit reservation). If E26 Discovery confirms Trigger (ii) is satisfied (timer requires multi-context-event ordering not satisfiable by per-context event handlers), the L2-stays pre-approval is **revoked for E26** and a new Discovery + amendment-DEC is required before E26 implementation may proceed.
- **E27 (`slotopt-integration`):** L2-stays pre-approval **applies unconditionally** based on audit-(v) findings (single-context-write pattern; external integration boundary per DEC-11). E27 per-epic Discovery should confirm scope-preview findings; if any TM-exposed REST status endpoint introduced at E27 produces a Trigger (i)/(ii) firing on cross-context orchestration, the standard escalation per D3 below applies.

### D3 — L2.5 escalation path preserved

DEC-45 does NOT eliminate or weaken DEC-40 Clause C trigger conditions (i) and (ii). It administratively pre-approves the trigger-iii structural-only Trigger α firing for the already-known Wave-2 trajectory.

If, at any time during Wave-2 implementation (E25, E26, or E27), a story's per-epic Discovery surfaces a service satisfying:

- DEC-40 Clause C Trigger (i) — service reads AND writes multiple bounded contexts in single transactional scope, AND event-driven decoupling insufficient; OR
- DEC-40 Clause C Trigger (ii) — scheduled/async job spans multiple bounded contexts with ordering/consistency requirements not satisfiable by per-context event handlers;

then a **NEW Discovery session** is required to produce an amendment-DEC introducing `de.vvwt.tm.application` per DEC-40 Clause C. Silent L2.5 introduction is forbidden. The L2-stays pre-approval covers Trigger-α-driven re-examination only; it does NOT pre-approve violations of Clause C trigger (i)/(ii).

---

## Impact

- **DEC-40** receives a pointer paragraph "2026-04-26 Amendment — Clause C L2.5 verdict per DEC-45" + frontmatter `last_updated_at` advance + `amended_by` += `DEC-45`. DEC-40 textual clauses (A, B, C, D, E) remain UNCHANGED — DEC-45 is a verdict-recording amendment, not a textual modification of trigger conditions.
- **E25 resumption Discovery** (separate session, per `project_e25_discovery_paused_pfade.md`): may proceed under DEC-45 D2 pre-approval without Trigger-α-driven re-examination ceremony.
- **E26 per-epic Discovery** (separate session, after DEC-45 lands): MUST conduct empirical Trigger-(ii) examination for timer's WebSocket pattern as part of audit-in-session. If audit confirms NOT satisfied, DEC-45 D2 PROVISIONAL pre-approval converts to firm. If audit confirms Trigger (ii) satisfied, escalate per D3.
- **E27 per-epic Discovery** (separate session): may proceed under DEC-45 D2 unconditional pre-approval; should confirm scope-preview findings + apply D3 escalation if cross-context Trigger fires.
- **`patterns/conventions.md`** is NOT directly updated by DEC-45 (no procedural pattern change beyond the Trigger-α-specific pre-approval). Conventions.md updates are paired with DEC-44 only.
- **DEC-37** remains unchanged. DEC-37 Clause C async-event-queue triggers (i/ii/iii observability-driven) are independent of DEC-40 Clause C L2.5 triggers (i/ii/iii structural). Both happen to use Greek-letter naming; both are in force; neither subsumes the other.
- **No backlog impact beyond E39+E39S01.** DEC-45 is a verdict-recording DEC; the operationalization is implicit (Wave-2 epics proceed under the pre-approval). No new stories arise from DEC-45 alone.
- **Future-Discovery citation:** when E25 / E26 / E27 Discovery resumes, the L2-stays verdict is citable as DEC-45 instead of being re-derived. Reduces ceremonial overhead; preserves substantive trigger checks per D3.

---

## Alternatives ruled out

- **Bundle DEC-44 + DEC-45 into a single DEC** (DEC-40 expansion-rule explicit allowance for the "coincidence path"). User-rejected at scope-question 1: 2 separate DECs for citation clarity. DEC-44 = mechanism (IT-annotation), DEC-45 = architectural verdict (L2 stays).
- **Defer L2.5 verdict to each Wave-2 per-epic Discovery** (no DEC-45). Rejected: leaves the governance-trail-gap from E24S06 unresolved; forces ceremonial L2.5 re-examination at every context-addition.
- **L2.5 escalation now** (introduce `de.vvwt.tm.application` module pre-emptively). Rejected: no concrete cross-context application-service exists today (audit (iv)+(v) confirm Trigger (i) NOT satisfied; Trigger (ii) UNVERIFIED for E26 only). Per DEC-40 Clause C explicit YAGNI guard: "Introducing an empty `application` module signals a boundary that has no enforcement reality."
- **Pre-approve the entire Modulith trajectory (Wave-2 + Wave-3+ + future)** without bounded scope. Rejected: would erode DEC-40 Clause C trigger discipline indefinitely; bounded pre-approval to known Wave-2 trajectory is the proportional response.
- **Skip the per-epic Trigger-(i)/(ii) reservations** and grant blanket pre-approval. Rejected: Trigger-(ii) for E26 timer is empirically unverified at scope-preview level (audit (iv)). Granting blanket pre-approval risks silent L2 lock-in on a Wave-2 epic that may legitimately require L2.5.

---

## References

- Session Brief: `discovery-2026-04-26-pfade-wave2-architecture-review` (Tier-2 Reviewer cycles 1+2 PASS_WITH_NOTES; human-validated 2026-04-26)
- Audits:
  - `contexts/artefacts/audits/E25-discovery-pfade-e26-timer-scope-preview.md` — Audit (iv), Trigger-(ii) reservation for E26
  - `contexts/artefacts/audits/E25-discovery-pfade-e27-slotopt-scope-preview.md` — Audit (v), L2-stays for E27
  - `contexts/artefacts/audits/E25-discovery-legacy-inventory.md` — E25 audit (i), L2-stays for E25
- Companion DEC: **DEC-44** — DEC-40 Clause E §Sub-Clause-3 activation (IT-annotation switch)
- Related DECs:
  - DEC-37 — Selective async + cascade serialization (independent triggers; both in force)
  - DEC-40 — Primary-Adapter-Isolation (amended by DEC-45 verdict-recording)
  - DEC-44 — companion DEC (this session)
- Industry references:
  - Spring Modulith reference samples (`docs.spring.io/spring-modulith/reference/`)
  - DEC-40 § Alternatives ruled out — L2.5 YAGNI guard
