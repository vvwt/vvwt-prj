<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-51.md at 97cf9790d64d275c479d302816ee67d7833af819 2026-05-08 -->
---
id: DEC-51
domain: governance
level: operational
title: "Amendment to DEC-47 — codify post-`claude -p`-exit invocation point of the existing 3-condition evidence-gate at delivery-daemon.sh on_exit fall-through branch (no new conditions, no Clause modification); extend Daemon-restart criterion to require E17S18 done"
status: active
amends: DEC-47
created_by: discovery
created_at: 2026-05-04
last_updated_by: discovery
last_updated_at: 2026-05-04
supersedes: null
superseded_by: null
tags:
  - daemon
  - close-story
  - evidence-gate
  - escalated
  - terminal-state-write
  - delegated-authority
  - bug-triage
  - defense-in-depth
  - schedulewakeup
  - headless-claude-p
  - amendment
  - dec-47-amendment
related_to: [DEC-22, DEC-27, DEC-47]
session_brief_ref: discovery-2026-05-04-e17s18-schedulewakeup-orphan-process-death
skills_invoked: [generate-decisions]
---

# DEC-51 — Amendment to DEC-47: post-`claude -p`-exit invocation point of the 3-condition evidence-gate

## Context

On 2026-05-04 the Discovery Agent investigated two stories whose Delivery
sub-agents died cleanly mid-CI-wait without ever writing the closing
`chore(${sid}): done` commit:

- **E46S05** (proof-point of root cause). The Delivery log
  `.gaai/project/contexts/backlog/.delivery-logs/E46S05.log` ends with a
  `ScheduleWakeup(delaySeconds: 270, prompt: "Check CI status for PR 179...")`
  invocation followed by `terminal_reason: "completed"`. The `claude -p`
  process exited cleanly and the harness reported success, yet no done-commit
  ever landed and PR #179 was abandoned in the OPEN state. The story sat
  `in_progress` until manual operator intervention.

- **E46S02** (manually closed by operator). Same fingerprint. Closed only
  after the operator wrote a non-standard reconciliation commit
  `chore(E46S02): mark done in backlog — PR #180 merged` outside the
  standard `close-story.sh` path.

By empirical contrast, **E46S01, E46S03, E46S04** completed normally — their
`.delivery-logs/E46S0[134].log` files contain zero `ScheduleWakeup`
invocations beyond the harness's init-list reference in the log header. The
presence of `ScheduleWakeup` in the post-PR-create phase is correlated 100%
with the failure mode in this evidence set.

### Root cause (two layers)

1. **Agent-side.** `ScheduleWakeup` is a Claude Code `/loop` dynamic-mode
   tool — it suspends the conversation and schedules a future re-entry. In an
   interactive Claude Code session this re-fires the same prompt later. **In a
   headless `claude -p` subprocess** the wakeup is orphaned at process exit
   because there is no persistent harness to re-deliver the scheduled prompt.
   The Delivery agent reaches for `ScheduleWakeup` on its own initiative — it
   is part of Claude Code's default tooling. Zero references to it exist in
   any GAAI delivery skill or agent identity file (verified by grep against
   `.gaai/core/skills/delivery/` and `.gaai/core/agents/delivery.agent.md`
   pre-E17S18). The `ci-watch-and-fix` skill (SKILL-DELIVERY-CI-WATCH-001)
   **already** prescribes the correct approach: synchronous in-process polling
   with heartbeat lines and an explicit prohibition on
   `gh pr checks --watch`. The agent did not follow it.

2. **Daemon-side.** The DEC-47 3-condition evidence-gate at
   `delivery-daemon.sh:check_stale_in_progress` correctly fires on stale
   `in_progress` rows — but only **after** `STALENESS_THRESHOLD` (default
   `DELIVERY_TIMEOUT + 600 s`, ~3.5 h). Between Delivery process death and
   gate evaluation lies a ~3.5 h window during which the backlog row is
   observably stale yet the daemon takes no action. PR #179 had no CI
   workflows registered (`gh pr checks` returned "no checks reported") —
   Delivery did not recognize this as a terminal "CI PASS (no checks)" state
   per the `ci-watch-and-fix` skill's Step 1b, and the daemon had no fast
   path to detect the orphan.

### Steel-man of single-layer alternatives

- **Agent-side prohibition only (no daemon-side fast-detect).** Rejected:
  too soft — under future model drift the agent could ignore the prohibition,
  re-introducing the failure mode silently. The 3.5 h dead window remains
  even when the prohibition holds because process death from any cause
  (kernel kill, OOM, network partition) reaches the same orphan state.
- **Daemon-side fast-detect only (no agent-side prohibition).** Rejected:
  leaves the 3.5 h delay between exit and detection in the path where the
  agent reaches for `ScheduleWakeup` deliberately; even with the new
  invocation point, that 3.5 h still applies if the agent never had the
  prohibition in front of it. Cooperative defense-in-depth shrinks the
  failure window to seconds for the typical case.

**Both layers ship in the same Story (E17S18)** for cohesion and atomic
rollback (precedent: E17S16 single-commit pattern for DEC-47 + close-story
hardening + evidence-gate operationalization).

### Honest accounting of what is accepted

The new invocation point introduces no new gate, no new conditions, no
modified Clause. It reuses the EXISTING 3-condition evidence-gate from
DEC-47 Clause A via a shared bash library (`lib/post-exit-gate.sh`) that
both `check_stale_in_progress` (legacy ~3.5 h staleness path) and the
wrapper's `on_exit` fall-through branch (DEC-51 post-exit fast path)
source. Per AC-POST-EXIT-GATE-EVALUATES-3-CONDITIONS, duplication of the
gate logic is forbidden.

DEC-47 Clause F asymmetric-error preference (false-escalate ≫ false-fail)
is preserved unchanged at the new call site — `check_stale_evidence`'s
existing FALSE-favoring ambiguity handling carries through.

DEC-47 Clauses A/B/C/D/E/F themselves are NOT modified by this amendment.
DEC-47.md gains only a `related_to: DEC-51` back-reference and a 1-line
annotation in its §Daemon-restart criterion section pointing at this DEC's
superseding criterion.

---

## Decision

DEC-47 is amended by adding ONE clause and extending the Daemon-restart
criterion. All existing DEC-47 clauses (A–F) remain UNCHANGED in their
textual content and authority.

### Clause G — post-`claude -p`-exit invocation point (NEW)

The DEC-47 3-condition evidence-gate (Clause A: no done-commit on any branch
∧ no `verdict: PASS` impl-or-qa-report on disk ∧ pr_status ≠ merged) MUST
be evaluated immediately on `claude -p` subprocess exit when ALL of the
following hold:

- the wrapper's `EXIT_CODE == 0` (clean exit reported by the harness), AND
- the backlog row's status is NOT in {`done`, `failed`, `escalated`} (i.e.,
  the row sits `in_progress` despite the clean exit — the bug fingerprint).

The invocation point is the **`on_exit` fall-through branch** in the wrapper
script generated by `delivery-daemon.sh`'s `launch_delivery_tmux` and
`launch_delivery_terminal` functions — the previously-no-op branch that lets
the bug fingerprint slip past today.

The branch MUST NOT extend the existing `EXIT_CODE != 0` branch (which
already writes `failed [delivery-wrapper]` legitimately). It MUST NOT fire
when status is already terminal. The fingerprint is precisely "clean exit +
status still in_progress" — attaching elsewhere misses the entire failure
mode.

#### Verdict semantics at the new call site

- **Gate-TRUE** (all three Clause A conditions TRUE → legitimate orphan):
  write `chore(${sid}): failed [daemon-post-exit-gate]` per Clause A under
  `with_staging_lock`. The commit-tag string `[daemon-post-exit-gate]` is
  distinct from `[daemon-staleness]` (legacy threshold path) and from
  `[delivery-wrapper]` (already-existing exit-code-nonzero path) so
  operator forensics can distinguish the three sources at-a-glance.
- **Gate-FALSE** (any Clause A condition FALSE → evidence-positive):
  invoke close-story-replay once (idempotent per Clause D). On replay-success
  the row becomes `status: done` via `close-story.sh`'s normal closure path.
  On replay-failure the daemon writes
  `chore(${sid}): escalated [daemon-post-exit-evidence-positive]` per
  Clause C — same `escalated`-write authority as the legacy path,
  with a distinct commit tag for forensics.

#### Concurrent-deliveries scoping

The post-exit handler MUST scope its gate evaluation to the EXITed
subprocess's `${sid}` only. The trap is per-wrapper and fires once per
subprocess exit — naturally scoped. Sibling deliveries running in parallel
under their own wrappers MUST NOT have their backlog rows touched by the
post-exit handler. A naïve "scan all in_progress rows on every exit"
implementation would falsely route still-running siblings through the gate
and is forbidden.

#### Operator-visibility log line

The post-exit gate MUST emit a single operator-actionable log line on each
evaluation, naming the story id, the gate verdict, the chosen write target,
and the per-condition reason — same shape as DEC-47 Clause B already
prescribes for `check_stale_in_progress`. Example:
`[post-exit-gate] story=E46S05 gate=TRUE write=failed reason=condition_a_no_done_commit + condition_b_no_PASS_report + condition_c_pr_not_merged`.
The line MUST be emitted to the daemon's main log stream — the same
destination the existing Clause B log lines use (typically the tmux pane
stdout the daemon runs under, or whichever sink is shared by the daemon's
existing `log()` helper). Emitting to a separate file would defeat
operator triage via `tail -f`.

### Daemon-restart criterion (EXTENDED — supersedes DEC-47's three-condition list)

DEC-47's §Daemon-restart criterion required (a) E17S16 done on staging,
(b) all new closure regression tests under `tests/closure/` GREEN locally,
(c) operator manually restarts via `daemon-start.sh`. After the 2026-05-04
discovery of the ScheduleWakeup orphan failure mode and authoring of this
amendment, the criterion is extended to require ALL of the following:

1. **E17S18** is `status: done` on `staging` (delivered, QA PASS, merged
   via the governance-only path — no PR is created since the outer repo's
   remote is not on a known GitHub host; closure proceeds via
   `close-story.sh` with the governance-bypass branch per the E17S16/E17S17
   precedent).
2. The four new closure regression tests under
   `.gaai/core/scripts/tests/closure/` are GREEN locally:
   - `test_daemon_post_exit_immediate_gate.sh`
   - `test_daemon_post_exit_close_story_replay.sh`
   - `test_delivery_no_schedulewakeup.sh`
   - `test_daemon_post_exit_concurrent_scope.sh`
3. The pre-existing `tests/closure/` suite continues to pass GREEN
   locally (no regression of the E17S16 fixtures
   `test_daemon_staleness_evidence_gate.sh`,
   `test_daemon_staleness_genuine_failure.sh`, etc.).
4. Operator manually restarts via `daemon-start.sh` (no auto-restart on
   E17S18 closure).

Until then, the daemon stays paused (since DEC-47 2026-04-27 stoppage).

---

## Scope

In scope of DEC-51:

- The new post-`claude -p`-exit invocation point of the 3-condition
  evidence-gate at `delivery-daemon.sh` `on_exit` fall-through branch
  (Clause G).
- The extension of DEC-47's Daemon-restart criterion to require E17S18 done
  + new closure regression tests GREEN.
- The asymmetric-error preference (DEC-47 Clause F) at the new call site —
  inherited unchanged.

Out of scope of DEC-51 (handled separately or unchanged):

- Recovery of E46S05 / E46S02 — handled separately per Brief S-2 of
  `discovery-2026-05-04-e17s18-schedulewakeup-orphan-process-death` (and
  E46S05 already operator-reconciled).
- Modifications to DEC-47 Clauses A/B/C/D/E/F themselves — all preserved
  textually.
- Per-story timeout tuning (e.g., changing `STALENESS_THRESHOLD`) — out of
  scope.
- Alternative async-CI-watch mechanisms (separate watcher daemon,
  webhook-driven notifications) — out of scope.
- Modification of the `ci-watch-and-fix` skill's polling pattern itself —
  the existing pattern is correct; only the `ScheduleWakeup` prohibition is
  added (E17S18 AC-DELIVERY-SKILL-PROHIBITS-SCHEDULEWAKEUP).

---

## Implementation

E17S18 is the operationalization story for DEC-51. E17S18's acceptance
criteria cover:

- The shared library extraction at `lib/post-exit-gate.sh` exposing
  `check_stale_evidence` (reused by `check_stale_in_progress`) AND
  `run_post_exit_gate` (the Clause G action used by both wrappers).
- The new `on_exit` fall-through branch in both `launch_delivery_tmux` and
  `launch_delivery_terminal` wrapper templates (attached at
  `EXIT_CODE == 0 && current_status NOT IN {failed, escalated}` — the
  previously-no-op fall-through; the `done` case is already handled by the
  first branch above).
- The agent-side prohibition in `ci-watch-and-fix/SKILL.md` and
  `delivery.agent.md` (defense-in-depth Layer A).
- Four RED-first regression fixtures under
  `.gaai/core/scripts/tests/closure/` per DEC-22 Q-1a:
  `test_daemon_post_exit_immediate_gate.sh`,
  `test_daemon_post_exit_close_story_replay.sh`,
  `test_delivery_no_schedulewakeup.sh`,
  `test_daemon_post_exit_concurrent_scope.sh`.
- DEC-47 dual edit (`related_to: DEC-51` back-reference +
  Daemon-restart-criterion section annotation).

This DEC and E17S18 ship in a single atomic story-branch commit per the
precedent set by DEC-47+E17S16 (commit `cf2ebf6`-style single Discovery
commit) and the DEC-50 / DEC-48 delta-amendment precedent.

---

## Consequences

### Positive

- The bug fingerprint "clean `claude -p` exit + status still in_progress"
  is now caught **within seconds** of subprocess exit instead of waiting
  ~3.5 h for `check_stale_in_progress` — eliminating the dead window.
- Defense-in-depth: agent-side prohibition prevents reach-for-tooling
  drift; daemon-side fast-detect handles every other process-death cause
  (kernel kill, OOM, network partition) regardless of the agent's tool
  choice.
- DEC-47's three-condition gate is reused unchanged — no new attack
  surface, no new failure mode introduced by the amendment itself.
- `escalated` rather than `failed` is the safe-default verb when evidence
  suggests work-may-be-complete (DEC-47 Clause F asymmetric-error
  preference inherited at the new call site).

### Negative / accepted

- One additional invocation point of the gate logic — increases the
  blast-radius of any future bug in `check_stale_evidence`. Mitigated by
  shared-library refactor (single source of truth) and the four new
  RED-first regression tests guarding the new call site.
- The prohibition is a soft control on its own — model drift can erode it.
  Mitigated by the daemon-side fast-detect being independently sufficient
  for the orphan-detection contract; the prohibition is the cooperating
  layer that shrinks the typical failure window from minutes to seconds.

### Neutral / informational

- The legacy `check_stale_in_progress` ~3.5 h staleness gate is preserved
  unchanged as a safety-net for cases where the daemon did NOT witness
  the `claude -p` exit (daemon restarted between exit and detection,
  exit-pid lost, kernel killed the subprocess without harness
  notification). DEC-51 does not retire that path — both invocation points
  serve disjoint failure-detection purposes (fast-path for witnessed
  exits; safety-net for unwitnessed exits).

---

## Related decisions

- **DEC-47** — base DEC; this amendment extends DEC-47's three-condition
  evidence-gate to a new invocation point. DEC-47 Clauses A–F unchanged.
- **DEC-22** — TDD project-wide; E17S18's four new fixtures follow Q-1a
  RED-first.
- **DEC-27** — Post-Delivery Report-Pflicht; impl-report + qa-report on
  disk are the evidence signals Clause A(b) reads at both invocation
  points.
- **DEC-13** — staging/main branch model; daemon push-target remains
  `staging`; the post-exit-gate failed/escalated commits target `staging`
  exactly like the legacy path.
- **DEC-50 / DEC-48 / DEC-46** — delta-amendment-pattern precedent (this
  DEC follows the same locality-bounded amendment style).

---

## References

- Session Brief:
  `discovery-2026-05-04-e17s18-schedulewakeup-orphan-process-death`
  (Discovery Agent human-validated 2026-05-04; Tier-2 review cycle-1
  PASS_WITH_NOTES with 0 critical / 0 high / 3 medium / 3 low — all 6
  incorporated into the story body pre-registration; rubric_version
  2026-03-30; no cycle 2 — PASS-tier verdict).
- Implementing Story: **E17S18**
  (`.gaai/project/contexts/artefacts/stories/E17S18.story.md`).
- Empirical evidence: `.gaai/project/contexts/backlog/.delivery-logs/E46S0[1-5].log`
  — empirical contrast across 5 deliveries; `ScheduleWakeup` invocation
  correlated 100% with the failure mode (E46S02, E46S05) and 0% with the
  successful deliveries (E46S01, E46S03, E46S04).
- File references:
  - `.gaai/core/scripts/lib/post-exit-gate.sh` — shared library exposing
    `check_stale_evidence` (reused by both invocation points) and
    `run_post_exit_gate` (the Clause G action).
  - `.gaai/core/scripts/delivery-daemon.sh` — sources the library at top;
    wrapper `on_exit` in both `launch_delivery_tmux` and
    `launch_delivery_terminal` carries the new fall-through branch.
  - `.gaai/core/skills/delivery/ci-watch-and-fix/SKILL.md` — agent-side
    prohibition layer.
  - `.gaai/core/agents/delivery.agent.md` — agent-side prohibition layer
    (Forbidden Patterns section).
- DEC-47 textual edits as part of E17S18:
  - frontmatter `related_to:` includes `DEC-51` (back-reference).
  - §Daemon-restart criterion gains 1-line annotation
    `*Updated 2026-05-04: superseded by DEC-51 § Daemon-restart criterion ...*`
    pointing at this DEC's extended criterion.
