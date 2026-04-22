<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-31.md at 73724d48f1cd9b16778846cc2501c5eb697e9a83 2026-04-22 -->
---
id: DEC-31
domain: governance
level: operational
title: "Wave-2 governance-artefact propagation automation contract (Delivery-Skill invoked by `coordinate-handoffs`, Approach A)"
status: active
created_by: delivery
created_at: 2026-04-19
last_updated_by: delivery
last_updated_at: 2026-04-19
supersedes: null
superseded_by: null
tags:
  - governance
  - artefact-propagation
  - delivery-skill
  - wave-2
  - automation
related_to: [DEC-13, DEC-22, DEC-23, DEC-27, DEC-28]
---

# DEC-31 — Wave-2 Governance-Artefact Propagation Automation Contract

## Context

DEC-23 §Wave-2 explicitly defers the design of the propagation tooling: *"A Wave-2
Discovery story will design and implement the automation: a Delivery skill extension
that, at story-closure commit time, identifies the `related_decs` of the closing story
and copies any not-yet-propagated DECs + the story itself into
`vvwt-prj/docs/governance/`, appends to `DEC-INDEX.md`, and commits the snapshot."*

The Wave-2 Discovery session (2026-04-19) resolved the deferred tooling design via
approach-evaluation WAVE2-GOV-001, choosing among three candidate integration points:

- **(A) Delivery-Skill extension** — a new skill
  `.gaai/core/skills/delivery/propagate-governance/SKILL.md` invoked inline by
  `coordinate-handoffs` between step 7 (merge to `vvwt-prj/staging`) and step 10
  (backlog update to `done`).
- **(B) Daemon-scheduler gate** — the wrapper-side `delivery-daemon.sh` pre-flight
  check performs the propagation commit before picking the next story.
- **(C) Hybrid: Delivery-Skill primary + Daemon compensating gate** — Skill (A)
  handles propagation; Daemon (B) provides a read-only verification gate that BLOCKS
  next-story launch on gap detection.

**Approach A was chosen.** Rationale:
- **DEC-23 wording fit**: the DEC-23 §Wave-2 paragraph exactly specifies "Delivery
  skill extension … at story-closure commit time" — A matches verbatim; B and C add
  components not named in the originating decision.
- **DEC-22 test-first fit**: the E17S01 (`gate-check.sh`, 68 tests) and E17S02
  (`retro-audit.sh`, 48 tests) fixture-harness patterns are proven for pure-bash
  Delivery scripts. A uses this same shape; B requires a daemon-fixture harness (new,
  unvalidated cost).
- **DEC-13 atomicity**: A's propagation commit is in-flow, adjacent to the
  code-cutover commit in the same session, on the same branch author. B's commit is
  detached by the daemon poll interval (up to minutes), breaking commit-graph legibility.
- **Reversibility**: A is low-lock-in (remove one call-site in `coordinate-handoffs`;
  skill file persists for manual invocation). C doubles the implementation scope and
  couples two test harnesses.
- **Wave-1 H-1 audit**: the trust-based gap concern (Approach A relies on the Delivery
  session completing normally between vvwt-prj merge and backlog done-write) was
  validated empirically by the E19S01 delivery — see §Audit below. Zero abnormal
  terminations in 20 E14/E15 sessions. Approach A's trust assumption is verified for
  the Wave-1 corpus.

**Not evaluated in this iteration (reserved for re-evaluation at escalation triggers):**
- *GitHub Actions on `vvwt-prj` PR merge*: considered in WAVE2-GOV-001 T-1 scope
  review but excluded because it introduces a CI/CD dependency incompatible with DEC-3
  (no proprietary services) unless using fully open-source Actions. Re-opens for
  evaluation if any escalation trigger (§Impact) fires.

**H-1 audit result (AC6, E19S01):** 20 Wave-1 delivery sessions audited
(E14S01–E14S12, E15S01–E15S08). Zero sessions terminated abnormally between
vvwt-prj/staging push and the `chore({id}): done [delivery]` outer-repo commit.
H-1 is VERIFIED for the Wave-1 corpus. The E14S11 gate false-positive occurred
post-session (wrapper-side re-check after the done-commit was already on staging) and
does not constitute an abnormal termination in the merge→done interval.

---

## Decision

**Adopt Approach A: implement a standalone Delivery skill
`.gaai/core/skills/delivery/propagate-governance/SKILL.md` and invoke it from
`coordinate-handoffs` between step 7 (merge to `vvwt-prj/staging`) and step 10
(backlog update to `done`).**

### Integration Contract (authoritative specification for E19S02 + E19S03)

**(a) Skill path.**
The propagation skill lives at:
```
.gaai/core/skills/delivery/propagate-governance/SKILL.md
```
Its pure-bash helper script and fixture harness follow the E17S01/E17S02 pattern
under `.gaai/core/scripts/tests/propagate-governance/` (no `bats` dependency, per
DEC-22's zero-new-external-tooling precedent).

**(b) Invocation point.**
`coordinate-handoffs` invokes `propagate-governance` **between its existing step 7
(squash-merge to `vvwt-prj/staging`) and step 10 (backlog-scheduler.sh
`--set-status {id} done`)**. The invocation is conditional on the story's
`delivery_mode`: see item (g) below.

**(c) Snapshot commit discriminator.**
The propagation step produces a **separate commit** on `vvwt-prj/staging`:
```
docs(governance): propagate {story-id} snapshot per DEC-31
```
This commit is distinct from the story's code-cutover commit per DEC-13 (code
commits are `feat({id}):` or `fix({id}):` prefixed; governance-snapshot commits
use `docs(governance):` prefix). Both land on `vvwt-prj/staging` in the same
Delivery session, adjacent in the commit graph, with the governance snapshot
immediately following the code merge.

**(d) Byte-identity invariant.**
Every file copied into `vvwt-prj/docs/governance/` MUST be byte-identical to its
source in the outer repo at the time of invocation. The skill MUST verify this
(e.g., via `diff -q`) after copy and FAIL LOUD (non-zero exit, structured error
message) if any byte differs. A byte-identity failure is a hard STOP — the skill
MUST NOT commit a non-identical copy.

**(e) DEC-INDEX.md append-at-bottom contract.**
New DEC rows are appended at the bottom of `vvwt-prj/docs/governance/decisions/DEC-INDEX.md`,
in propagation order (chronological by story-closure time). The DEC-INDEX.md is
NOT sorted alphabetically or numerically. This matches the order established by
the Wave-1 bootstrap (E13S05): DEC-20, DEC-21, DEC-22, DEC-23 appear in that
order in the existing DEC-INDEX.md — they were propagated in dependency order,
not DEC-number order.

**(f) Idempotent re-invocation.**
If a DEC or story file already exists in `vvwt-prj/docs/governance/` at byte-identical
content to the outer-repo source, the skill MUST treat it as already-propagated
and skip the copy without error. If the file exists but differs in content (a later
Discovery round amended the DEC in the outer repo), the skill MAY propagate a new
snapshot (updating the file) and MUST update the attribution comment to reflect
the new commit SHA. On idempotent re-invocation (nothing to propagate), the skill
produces no commit and exits cleanly with `git status --porcelain` empty.

**(g) `delivery_mode: governance-only` interaction.**
Stories with `delivery_mode: governance-only` (e.g., E19S01, E17S01, E17S02) are
governance-authoring sessions that do not produce code referenced from `vvwt-prj`.
Such stories do NOT trigger propagation of their own story file — their story artefact
is not code-referenced from `vvwt-prj` and placing it in `docs/governance/stories/`
would create a phantom entry with no code referent.

However, a governance-only story's `related_decs` ARE subject to future propagation:
when a subsequent `source`-mode story closes with any of the same DEC IDs in its
`related_decs`, the skill will propagate those DECs at that story's closure if not
already propagated. The governance-only story's DEC references remain authoritative
in the outer repo.

**(h) Attribution comment format.**
Every file propagated into `vvwt-prj/docs/governance/` MUST have the following
attribution comment on its first line:
```
<!-- Snapshot of outer-repo .gaai/project/contexts/.../{file} at {commit-sha} {date} -->
```
where `{commit-sha}` is the full 40-character SHA of the outer-repo staging commit
that is HEAD at propagation time (`git -C <outer-repo-root> log -1 --format=%H staging -- <path>`),
and `{date}` is the ISO 8601 date (YYYY-MM-DD) of propagation. This is the verbatim
format established by E13S05's Wave-1 manual bootstrap.

### `governance_snapshot_sha` Backlog Field

DEC-31 authorizes a new optional field in `active.backlog.yaml` under each delivered
story entry:

```yaml
governance_snapshot_sha: "<40-char commit SHA>"
```

This field records the `vvwt-prj` commit SHA of the governance snapshot produced by
the `propagate-governance` skill at story closure. It is written inline by the
Delivery skill; the `backlog-scheduler.sh` setter for this field is added by E19S03
(not this story).

**Default behavior for pre-DEC-31 stories**: field absent (grandfather parity with
DEC-27 §no-retrofit — pre-DEC-31 done stories are not retrofitted with this field).

**Write-path owner**: Delivery skill (`propagate-governance`) invokes
`backlog-scheduler.sh --set-field {id} governance_snapshot_sha <sha>` after the
snapshot commit is confirmed pushed.

---

## Impact

### Escalation Triggers for DEC-31 Amendment or DEC-32 (Hybrid-C)

The following three disjunctive triggers (from Brief S-2) require a DEC-31 amendment
OR a new DEC-32 adding Hybrid C (Delivery-Skill + read-only daemon gate):

**(a) Wave-1 audit reveals ≥1 abnormal-termination anomaly.**
This trigger was evaluated in E19S01 (AC6/AC7). Result: 0 anomalies in Wave-1 corpus
(E14+E15, 20 sessions). Trigger (a) does NOT fire. Approach A stands as written.

**(b) After E19 lands, ≥1 Wave-2 story closes with a post-hoc demonstrable
propagation-gap.**
If a Wave-2 `source`-mode story is marked `done` and its DEC snapshots are verifiably
absent from `vvwt-prj/docs/governance/` (i.e., the Delivery skill ran but failed to
commit, or was skipped), this trigger fires. Discovery evaluates whether to add the
Hybrid-C daemon gate or take another remediation path.

**(c) Cumulative ≥3 Wave-2 propagation-integrity incidents of any severity.**
If three or more incidents of any kind (missed snapshot, partial copy, byte-identity
failure, idempotency bug) accumulate across Wave-2, the Approach-A trust assumption
is empirically falsified. Discovery evaluates adding Hybrid C or adopting another
integration model.

At any trigger, the GitHub-Actions-based alternative (considered-but-out-of-scope in
WAVE2-GOV-001 §not-evaluated-alternative) re-opens for evaluation.

### Downstream Effects

- **E19S02 scope**: implements `propagate-governance/SKILL.md` + fixture harness under
  `.gaai/core/scripts/tests/propagate-governance/` (TDD-first per DEC-22). This DEC-31
  is the authoritative specification; E19S02 MUST cite DEC-31 in `related_decs`.
- **E19S03 scope**: edits `coordinate-handoffs/SKILL.md` to insert the
  `propagate-governance` invocation between its step 7 and step 10; adds
  `governance_snapshot_sha` setter to `backlog-scheduler.sh`; end-to-end integration
  fixture.
- **All subsequent Wave-2 `source`-mode stories** (Track-3 reconstruction epics,
  ~5–8 stories per epic × ~7 epics): automatically trigger `propagate-governance` at
  closure via `coordinate-handoffs`. They MUST include relevant DECs in `related_decs`
  frontmatter for the skill to know what to propagate.
- **DEC-23 §Wave-2 block** is updated with a one-line forward reference to DEC-31
  (the Wave-2 automation deferral is now implemented).
- **`delivery_mode: governance-only` stories** are unaffected at closure time
  (no propagation trigger). Their `related_decs` propagate at next `source`-mode
  story closure that references the same DECs.
- **DEC-8 remains unchanged.** Sub-projects with their own git history remain excluded
  from the outer GAAI repo. Propagation is one-directional: outer governance artefacts
  → `vvwt-prj/docs/governance/`. Not the reverse.
