<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-69.md at 756c6dd01fc404719ccba8bcd71e7b3a31cd66b5 2026-05-15 -->
---
id: DEC-69
domain: governance
level: operational
title: "Amendment to DEC-22: production Repository/DAO read methods require a production consumer — test-only read methods are forbidden (consumer-driven API)"
status: active
created_by: discovery
created_at: 2026-05-15
last_updated_by: discovery
last_updated_at: 2026-05-15
supersedes: null
superseded_by: null
amends: DEC-22
tags:
  - tdd
  - testing
  - governance
  - yagni
  - repository
  - dead-code
  - red-first
related_to: [DEC-22, DEC-26, DEC-29, DEC-54, DEC-67]
skills_invoked: [decision-extraction]
---

# DEC-69 — Production Repository/DAO read methods require a production consumer

## Context

DEC-22 activates the TDD Iron Law project-wide: *NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST*. The Iron Law is **unidirectional** — it governs how production code is *authored* (a failing test must precede it). It does not state the converse: that production code must exist *for a production purpose*. A method can satisfy the Iron Law (a test was written first) and still be wrong under TDD's intent — if the only thing that ever calls it is its own test.

A 2026-05-15 code audit (operator-initiated, originating from a finding on `AuditLogRepository`) enumerated every public method on all 45 Repository/DAO interfaces across the 3 persistence-bearing `vvwt-prj` modules (`vvwt-tm-web`, `vvwt-slotopt-dispatcher`, `vvwt-info-server`) and found **9 public read methods with zero production callsites** — exercised only by tests, or by nothing at all:

| Module | Repository/DAO | Method | prod / test |
|---|---|---|---|
| vvwt-tm-web | `tournament.AuditLogRepository` | `findByTournamentIdAndId` | 0 / 0 |
| vvwt-tm-web | `tournament.AuditLogRepository` | `findByTournamentIdAndMatchIdAndSetIndexOrderByChangedAt` | 0 / 1 |
| vvwt-slotopt-dispatcher | `audit.AuditRepository` | `findByWorkerIdOrderByOccurredAtDesc` | 0 / 2 |
| vvwt-slotopt-dispatcher | `audit.AuditRepository` | `findByEventTypeOrderByOccurredAtDesc` | 0 / 2 |
| vvwt-slotopt-dispatcher | `packet.PacketRepository` | `findByStatus` | 0 / 2 |
| vvwt-slotopt-dispatcher | `result.LateResultRepository` | `findByPacketId` | 0 / 2 |
| vvwt-slotopt-dispatcher | `result.ResultAuditRepository` | `findByPacketId` | 0 / 2 |
| vvwt-info-server | `persistence.audit.AuditLogDao` | `findById` | 0 / 0 |
| vvwt-info-server | `persistence.audit.AuditLogDao` | `findByRequestId` | 0 / 3 |

**Motivating incident — a QA-gate escape.** Story E55S13 (file-based audit-log re-implementation, merged 2026-05-14) renamed `AuditLogRepository.findById` to `findByTournamentIdAndId` under `AC-IMPL-READ-API-EXTENDED` and kept it in the public interface, but carried no test for it into the new `FileAuditLogRepositoryIT`. The method passed E55S13's QA both **untested and unconsumed** — violating E55S13's own `AC-GOVERNANCE-DEC-22-RED-FIRST`. The Iron Law's unidirectional framing did not catch it: there was no clause to violate.

**E55S13 Brief S-8** had explicitly deferred the audit-log read consumer to *"a future separate story"* — an anticipatory-API posture (speculative generality — Fowler, *Refactoring*). The operator reversed this stance on 2026-05-15: a read method is authored together with its production consumer, or it is not authored.

A test whose only purpose is to exercise a method that has no production consumer is **circular**: the test verifies the method, and the method exists for the test. TDD is consumer-driven design — tests are written for behaviour that production needs, not the reverse. Operator principle (2026-05-15): *"There must be no production-code methods created only for tests; if a test is hard to write, the production code is too complex and must be simplified"* — and its corollary, which this DEC codifies: production code that exists only to be tested should not exist.

## Decision

DEC-69 amends DEC-22 with a **scope clarification** — the converse direction of the Iron Law. It is not a weakening; it tightens.

1. **A public read method on a Repository or DAO MUST have at least one production (non-test) callsite.** A public read method on a Spring Data repository interface, a hand-authored repository/DAO interface, or a hand-authored DAO class — whose only callers reside in `src/test/`, or which has no caller at all — is **forbidden**. It is removed, together with any test that exercised only that method.

2. **Scope.** Applies to public *read* methods (query/finder methods) on Repository/DAO types across all `vvwt-prj` modules. **Excludes:**
   - **(a)** Spring Data inherited `CrudRepository` methods (`findById`, `save`, `deleteById`, `findAll`, …) — framework contract, not hand-authored API.
   - **(b)** Write methods (`save`, `insert`, `update`, `append`, `deleteBy*`) — DEC-69 governs the read-API surface only.
   - **(c)** Negative-contract / API-shape tests and the methods they assert *about* — e.g. a reflection test asserting an append-only DAO exposes no `delete*`/`update*` method. Such tests assert an invariant; they are not "test-only methods."
   - **(d)** A read method used by an integration test as the **assertion oracle for a production write-path** MAY still be removed under clause 1 — but the IT's write-path verification intent MUST be preserved via an alternative independent verifier (the integration test itself stays; only its dependency on the removed method changes). The mechanism of the alternative verifier is a Delivery decision.

3. **Removal is not authoring — no RED-first applies.** Removing a method authors no new first-party production code. DEC-22's Iron Law governs *authoring*; removal is governed by DEC-22's RED-GREEN-**REFACTOR** clause. Two removal classes:
   - **True orphan** (0 test, 0 production callsites): dead-code removal. The regression guard is the empirical zero-reference audit itself — "existing suite GREEN" proves nothing for a method no test touches.
   - **Test-only method** (≥1 test, 0 production callsites): the method and the test(s) that exercised only it are removed together — the legitimate REFACTOR phase of the original RED-GREEN-REFACTOR cycle. For a Spring Data derived-query method (no hand-authored body), the removed first-party code is the name-encoded interface declaration, which was itself authored RED-first (a RED-first IT preceded it); Spring's generated body needs no separate treatment. The existing suite staying GREEN (`mvn verify` exit-zero, DEC-54) is the regression guard.
   - DEC-67's dependency-coordinate non-authoring precedent is **not** the anchor — DEC-67 governs `pom.xml` version coordinates; method removal is a first-party source edit, governed here by DEC-22's REFACTOR clause.

4. **Enforcement — `qa-review` skill gains a step.** The `qa-review` skill (`SKILL-QA-REVIEW-001`) gains a new Step: for each public Repository/DAO read method **added or modified** in the story under review, `qa-review` verifies ≥1 production (non-test) callsite exists. A method whose only callsites are under `src/test/` → HIGH-severity finding → FAIL. The Step is added to `qa-review/SKILL.md` in this DEC's authoring session — a governance/skill change made through Discovery, not via a separate Delivery operationalization story.

5. **Revises E55S13 Brief S-8.** The posture *"anticipatory read API permitted; production consumer deferred to a future separate story"* is no longer acceptable. A read method is authored together with its production consumer, or it is not authored. E55S13's read API (`AC-IMPL-READ-API-EXTENDED`) is partially superseded — its read methods are removed; E55S13's write path and file-based implementation are untouched.

## Impact

- **DEC-22** gains an inline `## 2026-05-15 Amendment` paragraph pointing to DEC-69; frontmatter `amended_by` extends to `[DEC-34, DEC-36, DEC-41, DEC-54, DEC-67, DEC-69]`; `last_updated_at` advances to 2026-05-15. No DEC-22 Decision clause is modified — the Iron Law, JMH carve-out, reconstruction-in-place, characterization-test prohibition, and prior amendments DEC-34/36/41/54/67 remain TEXTUALLY UNCHANGED.
- **`qa-review` skill** gains a new Step (Consumer-Driven Repository Read-API Check) — added in this session.
- **Operationalized by E18S04** (Epic E18 *Code Hygiene Enforcement*) — the consolidated cleanup of the 9 audit findings + preservation of the 2 controller-IT audit-write assertions.
- **E55S13** is partially superseded for its read-API surface only (the 9th finding `findByRequestId` is on `vvwt-info-server`'s `AuditLogDao` — an independent subsystem, swept by the same DEC for the same pattern). E55S13 is not reopened; its body is not rewritten.
- **Production-used-but-untested methods** (`TournamentDao.findByTournamentToken`, `TournamentDeltaDao.insertDelta`) surfaced by the same audit are a **distinct** DEC-22 coverage gap (production code lacking a test) — out of DEC-69 scope; deferred to a separate Discovery cycle.
- **No supersession** — DEC-69 amends, does not supersede, DEC-22. It is authored from a code-audit finding plus a delivery-time QA-gate escape (E55S13), in the same post-mortem-driven amendment lineage as DEC-67.
