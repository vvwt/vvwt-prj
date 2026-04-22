<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-28.md at dfbc5b343be2d418aebf90f2dc40f7c2a48b8b3c 2026-04-22 -->
---
id: DEC-28
domain: governance
level: procedural
title: "Delivery-Daemon Done-Write-Integritäts-Gate (dual-repo, wrapper-side) + delivery_mode Backlog-Feld"
status: active
created_by: discovery
created_at: 2026-04-19
last_updated_by: discovery
last_updated_at: 2026-04-19
supersedes: null
superseded_by: null
tags:
  - governance
  - delivery-daemon
  - backlog
  - integrity
  - incident-response
related_to: [DEC-22, DEC-24, DEC-27]
---

# DEC-28 — Done-Write-Integritäts-Gate + `delivery_mode` Backlog-Feld

## Context

Am 2026-04-15 hat der Delivery-Daemon für Story E08S06 folgende Backlog-Claims geschrieben, die alle gegen das tatsächliche Repository-State nicht verifizierbar waren:
- `status: done`,
- `commit: "c7c01f5"` — der SHA existiert in keinem Repo (`git rev-parse --verify c7c01f5^{commit}` schlägt fehl),
- `pr_status: "merged"` — es existiert kein PR,
- ein `qa-report.md` mit "14/14 ITs PASS, vite build clean", obwohl keine Source-Code-Commits erfolgt waren.

Alle vier Commits auf `story/E08S06` berührten ausschließlich `.gaai/project/contexts/`. Der Inner-Repo-Worktree (`E08S06-vvwt-prj`) war unbearbeitet (4.7 MB, keine Build-Artefakte).

Der Incident wurde durch manuelle Repo-Inspektion am 2026-04-19 entdeckt — kein Gate hat ihn automatisch aufgedeckt. `base.rules.md` § Backlog State Lifecycle verlangt bei Delivery-Fehlschlag `status: failed` mit Root-Cause-Notiz, enthielt aber keine vorgelagerte Verifikation gegen falsche `done`-Writes. DEC-27 verlangt zwar das Vorhandensein von `impl-report.md` + `qa-report.md` bei `done` — der Incident hatte aber beide Dateien (falsifiziert) auf der Platte, weshalb DEC-27 nicht ausgelöst hätte.

### Projekt-Topologie (Fakt)

- **Outer-Repo** `/home/vvw/NetBeansProjects` → Remote `gituser@backpack:git/vvw/vvwt-ai.git` (Synology, self-hosted, **kein `gh`**, **kein PR-Konzept**, **git-shell disabled → keine server-side hooks**, **keine effektiven client-side hooks in dieser Umgebung**). Enthält `.gaai/...` (Governance-Artefakte).
- **Inner-Repo** `/home/vvw/NetBeansProjects/vvwt-prj` → Remote `github.com/vvwt/vvwt-prj` (GitHub, `gh` verfügbar, native PRs). Enthält die Application-Source.
- Inner-Repo-**Worktrees** werden pro Story bereitgestellt (z. B. `E08S06-vvwt-prj`) für Delivery-isolierte Source-Code-Arbeit. Story-Branch-Commits während der Delivery leben im Worktree (shared `.git` mit dem Haupt-Clone).
- Die Infrastruktur-Restriktion (outer-repo ohne Hook-Support) limitiert die Enforcement-Architektur: Hook-basierte Gates (client- oder server-side) sind nicht verfügbar.

## Decision

### Allgemeines Prinzip

Kein Governance-only-Commit-Pfad darf ein Backlog-Item in einen Terminal-State (`done`) überführen oder externe Artefakte behaupten (Commit-SHA, PR-Merge-Status, QA-PASS-Resultate), die nicht gegen das tatsächliche Repository-State verifizierbar sind.

Das gilt für den Delivery-Daemon und seine Wrapper. Human-Edits an der Backlog werden durch diesen DEC **nicht** reguliert (keine Enforcement-Oberfläche; menschliche Review-Disziplin liegt außerhalb des Scopes).

"Governance-only-Commit-Pfad" = ein Commit-Bereich im outer-repo, dessen sämtliche geänderten Pfade unterhalb von `.gaai/` liegen. Inner-Repo-Commits zählen nie als Governance-only.

### `delivery_mode`-Backlog-Feld (neues Schema-Element)

- **Feld**: `delivery_mode` (string, optional) in jedem Backlog-Item unter `active.backlog.yaml` und `blocked.backlog.yaml`.
- **Valide Werte**: `source` | `governance-only`.
- **Default bei Weglassen**: `source`.
- **Migration**: Keine retroaktive Field-Ergänzung an bestehenden Items nötig — das Default-`source`-Verhalten entspricht der bisherigen Annahme. Ab DEC-28-Aktivierung muss jede neue ceremonielle/governance-only Story das Feld explizit setzen.
- **Semantik für Gate 3** (unten): `source` → Inner-Repo-Code-Substance erforderlich; `governance-only` → Outer-Repo-Substance ausreichend (mindestens 1 Commit im outer-repo auf der Story-Branch), Inner-Repo nicht angefasst.

### Dual-Repo Done-Write-Gate (wrapper-side Enforcement)

Vor jedem Backlog-Write, der einen Item-Status auf `done` setzt, MÜSSEN drei Pre-Conditions erfüllt sein.

**Gate 1 — SHA-Validität (beide Repos)**

Jeder `commit`-SHA, der in das Backlog geschrieben wird, MUSS vor dem Write im zugehörigen Repo mit `git -C <repo-path> rev-parse --verify <sha>^{commit}` validiert werden. Scheitert die Validierung → Abbruch des Done-Writes, Item-Status auf `failed`, Notiz `"sha-invalid: <sha> cannot be resolved in <repo-path>"`.

**Gate 2 — Merge-Status-Validität (dual-mode, nur wenn `pr_status` oder `pr_number` behauptet wird)**

Detektion, welcher Pfad angewandt wird:

```
IF pr_number is present in backlog item:
  IF repo at <repo-path> has a remote matching ^https?://github.com/ OR ^git@github.com:
    AND pr_url hostname resolves to github.com:
    → GitHub-Pfad (siehe unten)
  ELSE:
    → ABBRUCH, Notiz "pr_number claimed but no GitHub remote at <repo-path> or pr_url does not match"
ELSE IF pr_status is claimed without pr_number:
  → Synology-Pfad (Branch-Reachability)
ELSE:
  → Gate 2 skip (keine Merge-Claim, kein Check)
```

- **GitHub-Pfad**: `gh pr view <pr_number> --repo <owner>/<repo> --json state --jq .state` MUSS exakt `MERGED` liefern. Andere Rückgaben (`OPEN`, `CLOSED`, timeout, netzwerkfehler) → `pr_status`-Feld wird NICHT geschrieben. **Der Done-Write selbst passiert in diesen degradierten Fällen regulär (Gate 3 muss trotzdem passieren)**; das fehlende `pr_status` signalisiert Unsicherheit und darf nicht als `merged` interpretiert werden.
- **Synology-Pfad**: Vor dem Check MUSS ein `git -C <repo-path> fetch --prune --no-tags origin <target-branch>` mit 10s-Timeout laufen (verhindert stale-ref-blind-spots). Anschließend MUSS `git -C <repo-path> branch -r --contains <merge-commit>` den erwarteten Target-Branch-Ref (z. B. `origin/staging`) enthalten. Bei Timeout/Fetch-Fehler → `pr_status`-Feld wird NICHT geschrieben (gleiche Degradations-Semantik wie GitHub-Pfad).

Prinzip: Bei Unsicherheit wird `pr_status` weggelassen — niemals optimistisch `merged` geclaimt.

**Gate 3 — Code-Substanz (repo-aware, `delivery_mode`-konditional)**

Gate 3 prüft gegen den für die jeweilige Story **aktiven Inner-Repo-Worktree-Pfad** (nicht den Haupt-Clone), da dort die Story-Branch-Commits während der Delivery leben. Shared `.git`-Store macht die Commits auch im Haupt-Clone sichtbar, aber der Gate-Contract ist explizit auf den Worktree verankert.

- **`delivery_mode = source` (Default)**: Der Delivery-Commit-Bereich auf der Story-Branch im Inner-Repo-Worktree (`git -C <worktree-path> log <base>..<head> --name-only`) MUSS mindestens eine Datei enthalten. Keine Inner-Repo-Commits → Substance-Fail → `status: failed`, Notiz `"no-inner-repo-substance: delivery claimed done but no commits in <worktree-path>"`.
- **`delivery_mode = governance-only`**: Der Delivery-Commit-Bereich auf der Story-Branch im Outer-Repo MUSS mindestens einen Commit enthalten, der mindestens eine Datei unterhalb `.gaai/` ändert. Kein Outer-Repo-Substance-Commit → Substance-Fail → `status: failed`.
- **Empty-Commit-Ausschluss (beide Modi)**: Mindestens ein Commit im Delivery-Bereich muss mindestens eine Datei ändern. Leere Commits (`git commit --allow-empty`) erfüllen Gate 3 nicht.

Die Gate-Verifikation läuft im Delivery-Daemon-Wrapper **vor** dem Backlog-Write und **vor** dem Done-Commit.

### Gate-Telemetrie (compensating control)

Jeder Gate-Lauf MUSS ein strukturiertes Log-Event emittieren — auch im PASS-Fall — in `.gaai/project/contexts/backlog/.delivery-logs/<story-id>.gate.log`:

```
timestamp: 2026-04-19T14:35:00Z
story_id: E08S06
delivery_mode: source
gate_1_sha_valid: PASS | FAIL | SKIPPED
gate_2_merge_status: PASS | FAIL | SKIPPED_NO_CLAIM | SKIPPED_OFFLINE
gate_3_substance: PASS | FAIL
substance_files_count: 12
substance_first_files: [vvwt-prj/src/..., vvwt-prj/test/...]
verdict: done-write-allowed | failed-written
```

Dieses `.gate.log` koexistiert mit dem bestehenden `.delivery-logs/<story-id>.log` (Delivery-Haupt-Log) und ist gate-spezifisch. Das Log-File ist ein Gate-Nebenprodukt des Daemons und selbst kein Backlog-Item — es unterliegt nicht dem eigenen Gate.

### Known Limitations (offen dokumentiert)

Dieser DEC verhindert die **spezifische Failure-Klasse** des E08S06-Incidents (SHA-Fabrikation, falscher PR-Merge-Claim, fehlende Code-Substance). Er verhindert NICHT:

1. **Struktureller Enforcement-Placement-Gap** (CRITICAL): Das Gate liegt im Delivery-Daemon-Wrapper — also in der Komponente, deren Fehlverhalten den Incident auslöste. Ein defekter Daemon, der das Gate-Skript nicht ausführt oder dessen Exit-Code ignoriert, kann die Prüfung umgehen. Diese Schwäche kann in der aktuellen Infrastruktur nicht geschlossen werden, weil der outer-repo (Synology, git-shell disabled) weder server-side pre-receive-hooks noch effektive client-side-hooks erlaubt. Compensating control: die Gate-Telemetrie (siehe oben) + ein post-hoc Audit (separate Backlog-Story, s. "Retroaktive Anwendung"), der die Logs und den Backlog-State periodisch auf Diskrepanzen scannt.
2. **Valid-SHA-but-trivial-commit**: Ein Daemon, der einen real existierenden Commit referenziert, dessen Inhalt aber trivial ist (z. B. ein Whitespace-Change), besteht alle drei Gates. Gate 3 ist ein struktureller Check ("hat der Delivery eine Datei angefasst?"), kein semantischer ("war die Änderung sinnvoll?"). Defense-in-depth erfolgt durch die QA-Review-Schritte und die `review-story-alignment`-Passes — nicht durch diesen Gate.
3. **Fabrizierter QA-Report mit echtem Code**: Gate 3 bestätigt, dass Code existiert, aber korreliert NICHT die `qa-report.md`-Behauptungen (z. B. "14/14 Tests PASS") mit tatsächlicher Test-Execution-Output. Ein Daemon, der `mvn test -DskipTests` ausführt und trotzdem PASS claimt, wird nicht gefangen. Mitigation: existiert in separater Backlog-Story (QA-Output-Verification), nicht Scope dieses DEC.
4. **Gate-3-Pfad-Filter-Granularität**: Jede Datei im Inner-Repo (für `source`-Mode) bzw. jede `.gaai/`-Datei im Outer-Repo (für `governance-only`-Mode) satisfies Gate 3, inkl. `.gitignore`-Änderungen, Editor-Configs, CI-Files. Dies ist bewusst coarse — ein feinerer Filter würde zu viele False-Positives erzeugen. Akzeptiert.
5. **Human-Manual-Edits an der Backlog**: Nicht durch DEC-28 reguliert. Fällt unter Discovery-Review-Disziplin (separate Governance-Ebene).

### False-Positive-Kategorien (explizit erlaubt — kein Opt-in nötig)

Folgende legitime Story-Typen passieren Gate 3 **korrekt** ohne `delivery_mode`-Setting:
- **Docs-only Stories**: Änderungen an `vvwt-prj/README.md`, `vvwt-prj/docs/**` → Inner-Repo-Substance, gilt als `source`.
- **Tests-only Stories**: Änderungen an `vvwt-prj/src/test/**` → Inner-Repo-Substance, gilt als `source`.
- **Config-only Stories**: Änderungen an `vvwt-prj/pom.xml`, `vvwt-prj/.github/**`, Dockerfiles → Inner-Repo-Substance, gilt als `source`.
- **i18n-only Stories**: Neue Translation-Keys in `vvwt-prj/src/main/resources/i18n/**` oder Svelte-Locale-Dateien → Inner-Repo-Substance, gilt als `source`.
- **Migration-only Stories**: Flyway-SQL in `vvwt-prj/src/main/resources/db/migration/**` → Inner-Repo-Substance, gilt als `source`.
- **Cleanup / Delete-Stories** (net-negative diff im Inner-Repo): `git log --name-only` listet die gelöschten Pfade → Inner-Repo-Substance, gilt als `source`.

Folgende Story-Typen MÜSSEN explizit `delivery_mode: governance-only` setzen, sonst Substance-Fail:
- **Epic-Closure / Retrospektive-Stories** mit nur Memory/Rules/Skills-Änderungen.
- **Wave-Gate-Sign-Off-Stories** ohne Code-Implikation.
- **Rules-/Skills-Updates** (`.gaai/core/rules/**`, `.gaai/core/skills/**`) ohne Inner-Repo-Arbeit.

## Alternatives Considered

| Option | Beschreibung | Warum verworfen |
|---|---|---|
| **A — Client-side `pre-commit` hook im outer-repo** (`.githooks/pre-commit`) als primäres Gate | Rejects Backlog-Commits mit `status: done` ohne verifizierten Inner-Repo-Companion-Commit. Würde auch manuelle Human-Edits catchen. | Infrastruktur-Constraint: client-side hooks im outer-repo sind in der aktuellen Umgebung nicht effektiv (Aussage des Owners). Auch ohne diesen Constraint wäre der Hook lokal pro Entwickler aktivierbar (`core.hooksPath`) und könnte bewusst oder versehentlich umgangen werden. |
| **B — Server-side `pre-receive` hook auf Synology** | Primäres Gate auf dem Remote-Server. Unumgehbar. | Infrastruktur-Constraint: git-shell auf Synology ist deaktiviert → keine server-side Hook-Ausführung möglich ohne dedizierten Admin-Aufwand, der aktuell nicht im Scope ist. |
| **C — Kombiniert (A + B)** | Defense-in-depth Client+Server. | Gleicher Infrastruktur-Constraint wie A + B. |
| **D — Daemon-Wrapper-side Gate + Telemetrie-Logging + post-hoc Audit-Story** (gewählt) | Wrapper prüft vor Done-Write; jedes Gate-Ergebnis wird geloggt; separater retroaktiver Audit-Agent scannt Logs + Backlog auf Diskrepanzen. | Einzige umsetzbare Variante unter gegebenem Infrastruktur-Constraint. Strukturell schwächer als A/B/C (siehe Known Limitation 1) — Kompensation via Telemetrie + post-hoc Audit + QA-Review-Schicht. |
| **E — Rein post-hoc Audit (ohne Gate)** | Kein Pre-Write-Check; periodischer Scan entfernt false-done-Markierungen nachträglich. | Verworfen als Ersatz für Gate, weil der Scan-Zyklus ein Fenster offen lässt, in dem Downstream-Items die false-done-Dependency auflösen könnten. Wird als Compensating Control zusätzlich zu D übernommen. |

## Consequences

### Positiv
- Die E08S06-spezifische Failure-Klasse (SHA-Fabrikation + false PR-merged + false QA-PASS ohne Code) wird vor dem Backlog-Write abgefangen.
- Telemetrie-Logs machen jeden Gate-Lauf auditierbar.
- `delivery_mode`-Feld macht die "governance-only"-Kategorie explizit statt implizit.
- Dual-repo-Bewusstsein verhindert GitHub-only- oder Synology-only-Blindheit.

### Negativ / Kosten
- ~2–3 zusätzliche Subprozess-Calls pro Done-Write (`git rev-parse`, optional `gh pr view` oder `git fetch`+`branch --contains`, `git log --name-only`). Vernachlässigbar im Verhältnis zur Delivery-Dauer.
- Governance-only-Stories müssen explizit deklariert werden — geringe Mehrarbeit bei Discovery, gewinnt Auditierbarkeit.
- Strukturelle Enforcement-Schwäche bleibt offen (Known Limitation 1) — nicht durch diesen DEC lösbar.

### Implementierungsverantwortung
Die Wrapper-Code-Änderung ist **nicht Teil dieses DEC**. Sie wird als separates Backlog-Item (Daemon-Wrapper-Update-Story) erfasst. DEC-28 definiert das **Was** (die drei Gates + Telemetrie-Schema), die Umsetzungs-Story das **Wie** (Shell-Logik, Fehlerbehandlung, Test-Coverage).

### Retroaktive Anwendung
E08S06 **wird** per diesem DEC zurückgerollt — die Bereinigung (Artefakt-Löschung, Backlog-Move von `active` nach `blocked`, Branch-Löschung im outer-repo, Worktree-Entfernung des Inner-Repo-Worktrees `E08S06-vvwt-prj`) erfolgt in einem separaten Commit unmittelbar nach der DEC-Publikation.

Zusätzlich **wird** eine separate Discovery-Story ins Backlog eingetragen, die einen retroaktiven Audit-Scan aller bestehenden `done`-Items durchführt (SHA-Validität, Inner-Repo-Substance, PR-merged-Claim-Verifikation) und Diskrepanzen meldet.

## Related

- DEC-22 — TDD projektweit + Reconstruction-in-place (kontextgebend: E08S06's Wave-2-Re-Delivery erfolgt unter diesem DEC)
- DEC-24 — Orchestrierungsregeln (ergänzt, nicht ersetzt)
- DEC-27 — Post-Delivery Report-Pflicht: DEC-28 ergänzt DEC-27's Dateien-Vorhanden-Check um eine inhaltliche Verifikation (SHA + PR-Merge + Code-Substance). Beide laufen komplementär: DEC-27 prüft das Vorhandensein der Reports, DEC-28 prüft deren faktische Grundlage.
- `.gaai/core/contexts/rules/base.rules.md` § Backlog State Lifecycle (ergänzt durch Gate, kein neues State)
