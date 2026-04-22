<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-13.md at 2e1377706eb015875185e98c87bf3f7926258e3b 2026-04-22 -->
---
id: DEC-13
domain: governance
level: operational
title: "Code sub-repositories adopt the staging/main branch model; AI Delivery targets staging only, humans promote staging→main"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - governance
  - branching
  - sub-repos
  - delivery
  - vvwt-prj
related_to: [DEC-8, DEC-10, DEC-11]
---

# DEC-13 — Staging/main branch model for code sub-repositories

## Context

The GAAI shell repository (`vvwt-ai`) enforces a `staging`/`production` branch model: AI Delivery targets `staging`, humans promote `staging` → `production` via reviewed PR. This model is documented in `.gaai/core/contexts/rules/orchestration.rules.md` § Branch Rules and in `.gaai/core/workflows/delivery-loop.workflow.md`. The Delivery Agent definition (`.gaai/core/agents/delivery.agent.md:40`) states: *"Merge its own PRs to production/main — `gh pr merge` targeting `main` or `production` is FORBIDDEN — the human merges to production after review. Self-merge to **staging** is PERMITTED after the diff-sanity check passes."*

Code sub-repositories (e.g., `vvwt-prj`) were never covered by this rule. Sub-repos have their own independent git history (DEC-8) and were bootstrapped with only a single `main` branch. During delivery of Epic E01 (Phase 1 slot-optimization, 2026-04-11), the Delivery Agent squash-merged every story branch (E01S01, E01S02, E01S03, E01S04, E01S05, E01S06, E01S07, E01S08, E01S09, E01S12) directly to `vvwt-prj/main` — not out of malice, but because no rule existed. The delivery.agent.md rule was ambiguously scoped: it clearly applied to the shell repo's `production` branch, but sub-repo `main` was never explicitly named.

This DEC closes that gap by extending the shell-repo branch model to code sub-repositories.

### Explicit scope clarification (interpretation vs extension)

This is an **extension** of delivery.agent.md § "no AI-self-merge to production/main", not a reinterpretation. The original rule was authored in the context of the shell repo's single-branch promotion flow. Applying it to sub-repos is a new governance scope — documented here so future reviewers understand the provenance.

### Steel-man of rejected alternatives

Three viable alternatives were considered and rejected:

1. **Trunk-based development with a required human reviewer on every PR (no `staging` branch).** Each delivery opens a PR from `story/{id}` directly to `main`; a human must approve before merge. Benefit: one fewer branch to reason about; merge-base is always `main`. Rejected because: (a) it requires a human in every delivery cycle, defeating the autonomous-by-default ethos of the daemon; (b) the shell repo does not use this model, so adopting it only in sub-repos creates an asymmetric mental model across the program; (c) the required-reviewer enforcement cannot be applied on the self-hosted gitea without server-side configuration that the user has explicitly declined (see alternative 3).

2. **Story branch lives until human merges it directly to `main`; no intermediate integration branch.** Each `story/{id}` is the integration point; multiple concurrent stories can coexist as open PRs against `main`. Benefit: simplest branch topology. Rejected because: (a) it offers no place for multiple stories to settle together before human review, which matters in `--max-concurrent > 1` mode where several stories can complete in parallel; (b) cross-story regressions would only surface at human-review time, not before; (c) it leaves the rate-limiting and ordering of human reviews as an unstructured queue.

3. **Server-side branch protection on `vvwt-prj/main` via GitHub branch-protection rules.** `vvwt-prj` is hosted on `github.com:vvwt/vvwt-prj.git`, which supports native branch-protection rules (block force-push, require PR, require reviews). Benefit: structural enforcement; the hook is a convention, branch protection is a hard server-side gate that cannot be bypassed by a rogue client. This alternative is particularly strong for vvwt-prj because github's branch-protection UI makes it a trivial toggle — not a multi-day infra project. Rejected by the user explicitly in this Discovery session: *"keine serverseitige Protection"*. The convention + client-side hook is accepted as the enforcement mechanism for now; server-side protection remains available as a zero-cost future hardening option and is not foreclosed by this DEC. If the drift hazard materialises, this is the cheapest mitigation.

### Honest accounting of what is lost

Adopting the staging model in sub-repos is not free. The following costs are accepted:

1. **Human throughput bottleneck.** Before this DEC, the daemon could deliver stories end-to-end without human action. Going forward, every finished story sits on `staging` waiting for a human to promote it to `main`. The autonomous loop now has a human-gated tail.
2. **Staging drift hazard.** If a human forgets to promote `staging` → `main`, `staging` and `main` drift apart. New story branches must decide whether to fork from `staging` (includes unpromoted work) or `main` (misses it). The delivery workflow update (see Impact below) standardises the answer: story branches always fork from `staging`.
3. **Merge-base ambiguity for hotfixes.** A hotfix applied directly to `main` must be cherry-picked to `staging` (or merged back) to keep them in sync. This is a new operational burden that did not exist when `main` was the only branch.

These costs are accepted as the price of consistency with the shell repo's governance model and with `delivery.agent.md`'s no-self-merge-to-main rule. The alternative — continuing ad-hoc direct-to-main merges — is rejected because it silently violates an explicit agent rule.

## Decision

For every code sub-repository governed by this GAAI shell (currently `vvwt-prj`; in future any additional code sub-repo such as a tournament-manager rebuild or a PPIS rebuild):

1. **`staging` is the AI integration branch.** AI Delivery creates story branches from `staging` (`git branch story/{id} staging`), works in them, and squash-merges the result back to `staging` after QA PASS.
2. **`main` is the human-gated production branch.** AI MUST NOT push to `main`, merge to `main`, or force-update `main`. Promotion `staging` → `main` is a human action.
3. **No history rewrite.** The current sub-repo `main` HEAD becomes the ancestor of the new `staging` branch. Existing AI-authored commits on `main` (from deliveries that happened before this DEC) remain in place as historical legacy. Reverting them would destroy working code without adding governance value, because no human review gate was ever applied to them anyway. Future deliveries follow the new rule.
4. **Enforcement = convention + client-side pre-push hook.** Each sub-repo carries a tracked pre-push hook at `.githooks/pre-push` that refuses `git push` to `main` unless the pusher sets `GAAI_ALLOW_MAIN_PUSH=1` in the environment. The hook is an escape-hatch model: it blocks accidental pushes by the daemon and sub-agents (neither sets the env var) while letting a human promote `staging` → `main` with an explicit opt-in. Every clone must set `git config core.hooksPath .githooks` after cloning for the hook to take effect. This is documented in the sub-repo's README.
5. **No server-side branch protection** (per user decision, this session). This is a governance-by-convention model, not a structurally enforced one. Accepted limitation — see the rejected-alternatives section above.

## Impact

- **orchestration.rules.md § Branch Rules** gains a new subsection "Code Sub-Repositories" documenting points 1–5 above, referencing this DEC. The existing shell-repo branch-rule section is unchanged.
- **delivery-loop.workflow.md** gains a new subsection "Sub-Repository Branch Model" at Step 0 (Git Setup), instructing the Delivery Agent to: (a) verify the sub-repo is on `staging` before starting work, (b) create story branches from `staging` not `main`, (c) squash-merge to `staging`, (d) never invoke `gh pr merge` or `git push` against `main` in a sub-repo.
- **`vvwt-prj`** gains a `staging` branch branched from the current `main` HEAD (`236457aabe36a1f603bb6fa9619861e310b17d9c` — the E01S06 squash-merge commit), a tracked `.githooks/pre-push` hook, and a local `core.hooksPath = .githooks` configuration. Pushed to `origin/staging`. The existing `main` branch is untouched.
- **Historical E01 commits on `vvwt-prj/main`** (currently: E01S00 bootstrap, E01S01, E01S02, E01S03, E01S06, E01S09) are **not** rewritten. They are the ancestor of the new `staging` branch. This DEC formally accepts them as legacy and closes the governance gap going forward. **Drift note:** the shell-repo backlog marks E01S04, E01S05, E01S07, E01S08, E01S12 as `done`, but those stories are NOT present on `vvwt-prj/main` — only on their respective `story/{id}` branches (S12 branch local only). This is a pre-existing reconciliation problem, not created by DEC-13, and is flagged here for a separate cleanup Discovery. DEC-13 branches `staging` from main as-is without attempting reconciliation.
- **Future code sub-repos** (TM rebuild, PPIS rebuild, any sibling Maven project) MUST be bootstrapped with a `staging` branch and the pre-push hook from the first commit. The bootstrap story for any such sub-repo cites this DEC in its `related_decs`.
- **Shell-repo pre-push hook gap.** `orchestration.rules.md` currently claims *"A pre-push hook (`.githooks/pre-push`) enforces this rule at the git level"* but no such file exists in the shell repo. This is a pre-existing documentation drift and is out of scope for this DEC. Flag for a separate governance cleanup.
- **Phase 1 slot-optimization impact.** Zero direct impact — Epic E01 is already done. DEC-13 governs Phase 2 and all future code sub-repo work.
- **Blocked stories E01S10 (TM submitter) and E01S11 (embedded worker in TM/PPIS).** These are blocked on future TM and PPIS sub-repo bootstraps. Those bootstraps MUST follow this DEC from the first commit.

## Residual risk

The staging-drift hazard (loss #2 above) has no technical mitigation in this DEC — it relies on human discipline to promote `staging` → `main` regularly. If drift becomes operationally painful, a future DEC may add either (a) a staleness-based daemon warning (e.g., "staging is 7 days ahead of main") or (b) server-side gitea branch protection with auto-forward. Both are deferred until drift is observed.
