# Governance Artefacts — Snapshot Model

This directory contains **point-in-time snapshots** of governance artefacts (DECs and
Stories) from the outer GAAI governance repository (`vvwt-ai.git`). The snapshots are
placed here so that external readers of `vvwt-prj` — GitHub visitors, contributors
without access to the outer repo, security reviewers — can resolve every `@see DEC-N`
or `@see E{N}S{N}` reference that appears in the code.

## Why Snapshots?

The authoritative governance artefacts live in a private GAAI repository. `vvwt-prj` is
public on GitHub. Without this directory, every governance reference in the codebase is
an unresolvable dead link for anyone who doesn't have the outer repo checked out alongside.

See **[DEC-23](decisions/DEC-23.md)** for the full decision rationale.

## Snapshot Semantics

- **Byte-identical at copy time.** Each snapshot matches the outer-repo source exactly at
  the commit recorded in its attribution comment (line 1 of each file).
- **Immutable after copy.** Snapshots represent the governance state _when the code was
  written_, not the current state. They are intentionally frozen.
- **Attribution comment.** Every snapshot file starts with:
  ```
  <!-- Snapshot of outer-repo {path} at {commit-sha} {date} -->
  ```
  This makes it trivially possible to verify freshness or diff against the outer repo.

## Directory Layout

```
docs/governance/
├── README.md              ← this file
├── verify-snapshot-integrity.sh  ← verifies byte-identity of all snapshots
├── decisions/
│   ├── DEC-INDEX.md       ← one-line per DEC with description
│   └── DEC-{N}.md         ← point-in-time copy of each referenced DEC
└── stories/
    └── E{N}S{N}.story.md  ← point-in-time copy of each Wave-1 story
```

## Verifying Snapshots

If you have access to the outer GAAI repo checked out alongside `vvwt-prj`:

```bash
# From vvwt-prj root:
./docs/governance/verify-snapshot-integrity.sh
```

Exit 0 = all snapshots match their sources at the recorded commit SHAs.
Exit 1 = one or more snapshots have drifted (names printed).

## Wave Coverage

**Wave 1 (this bootstrap):** DECs 20–23 and stories E13S01–E15S08 (19 stories).

Future waves add snapshots when new stories reference previously-unpropagated DECs or
introduce new governance artefacts.

## Code Reference Convention

Per **[DEC-23](decisions/DEC-23.md)** and `.gaai/project/contexts/memory/patterns/conventions.md`:

- Code uses stable IDs (`DEC-N`, `E{N}S{N}`) — these never change.
- When paths are needed (clickable Javadoc links), they point here:
  `docs/governance/decisions/DEC-N.md` or `docs/governance/stories/E{N}S{N}.story.md`
- No outer-repo paths (`.gaai/project/...`) in new code.
