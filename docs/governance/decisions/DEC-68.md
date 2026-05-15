<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-68.md at a1c258e0a305dfdc741bb6972392deb6da130c49 2026-05-15 -->
---
id: DEC-68
domain: architecture
level: architectural
title: "Tournament Manager filesystem-path canon — single configurable tm.data.dir root; every path derives its default from it; new path-needing features must derive, not add an independent root"
status: active
created_by: discovery
created_at: 2026-05-15
last_updated_by: discovery
last_updated_at: 2026-05-15
supersedes: null
superseded_by: null
tags:
  - data-directory
  - filesystem
  - configuration
  - deployment
  - self-host
  - persistence
related_to: [DEC-14, DEC-15, DEC-20]
skills_invoked: [decision-extraction]
---

# DEC-68 — Single tm.data.dir root for all Tournament Manager filesystem paths

## Context

The Tournament Manager writes to the filesystem from eight independent configuration points, split across **two** unrelated root directories:

| Config | Default root | Override env var |
|---|---|---|
| `spring.datasource.url` (bootstrap/shared H2 DB) | `~/.tournament-manager/db/tm` | `TM_DB_PATH` |
| `tm.data.dir` (per-tenant H2 DBs + `tenant-registry.json`) | `~/.vvwt-tm` | `TM_DATA_DIR` |
| `tm.audit-log.data-dir` (per-tournament JSONL audit log) | `~/.vvwt-tm` | `TM_AUDIT_LOG_DATA_DIR` |
| `tm.audio.data-dir` | `~/.tournament-manager/audio` | `TM_AUDIO_DATA_DIR` |
| `tm.photos.data-dir` | `~/.tournament-manager/photos` | `TM_PHOTOS_DATA_DIR` |
| `tm.certificate-templates.data-dir` | `~/.tournament-manager/certificate-templates` | `TM_CERT_TEMPLATES_DATA_DIR` |
| slotopt dispatcher `worker-key-dir` | `~/.tournament-manager/slotopt-keys` | `TM_SLOTOPT_WORKER_KEY_DIR` |
| `info-portal.keypair-dir` | `~/.tournament-manager/info-portal-keys` | `INFO_PORTAL_KEYPAIR_DIR` |

`tm.data.dir` is not even set in `application.yml` — `TmDataDirProperties` falls back to its Java-level default `${user.home}/.vvwt-tm`, which is why per-tenant DBs and (per E55S13) the audit log live under `~/.vvwt-tm` while everything else lives under `~/.tournament-manager`.

Beyond the eight configuration points, the same root literal is hardcoded again in first-party Java. Two of these carry the **wrong** root (`~/.vvwt-tm`) and must be corrected: `TmDataDirProperties` (Java field default) and `AuditLogConfig` (Java field default). A third, `DatabaseDirectoryInitializer.resolveDatasourceUrl()`, hardcodes `~/.tournament-manager/db/tm` in an `EnvironmentPostProcessor` fallback. Two more — `InfoPortalProperties` (Java field default) and `SlotOptimizationDispatcherConfiguration` (a `@Value` annotation default) — also hardcode a root literal, but already under `~/.tournament-manager` and therefore canon-consistent; they remain as static belt-and-suspenders fallbacks, because a `@Value`/field default cannot nest-resolve `${tm.data.dir}` when `application.yml` is absent (then `tm.data.dir` is undefined too). The jlink launcher scripts (`tournament-manager`, `tournament-manager.bat`) additionally hardcode `~/.tournament-manager/db/tm` as the `TM_DB_PATH` default.

The split accrued feature-by-feature: each path-needing story (E11 audio, E12 photos, E12S04 certificate-templates, E42 info-portal keypair, E55S13 audit-log) added its own independent path config and env var. No canon ever forced derivation from a single root, so two roots emerged by drift, not by design.

Two roots means two backup targets, two environment variables for an operator to learn, and two directories to document — friction directly against DEC-15's self-host model whose target user is a volunteer tournament organiser, not a systems administrator.

`DEC-20` § Decision specifies the per-tenant layout as `${tm.data.dir}/tenants/{tenant-uuid}/...` — it uses the `${tm.data.dir}` variable and explicitly defers "exact path/config" to an Epic-2 story AC. The concrete default `~/.vvwt-tm` came from story E14S02 AC9, not from DEC-20. Consolidating the root therefore does not contradict DEC-20 — it fixes the canon DEC-20 left open.

## Decision

1. **Single root.** `tm.data.dir` is THE Tournament Manager data-directory root. Default `${user.home}/.tournament-manager`. Operator override via the `TM_DATA_DIR` environment variable. `tm.data.dir` is set explicitly in `application.yml` — not left to a Java-level fallback.

2. **Every filesystem path derives its default from `${tm.data.dir}`:**
   - bootstrap/shared H2 DB → `${tm.data.dir}/db/tm`
   - per-tenant H2 DBs → `${tm.data.dir}/tenants/{tenant-uuid}/`
   - tenant registry → `${tm.data.dir}/tenant-registry.json`
   - per-tournament audit log → `${tm.data.dir}/tenants/{tenant}/audit-log/{tournament-id}/audit.jsonl` (the audit log co-inhabits the same `tenants/{uuid}/` tree as the per-tenant DBs — `tm.audit-log.data-dir` resolves to `${tm.data.dir}` itself)
   - audio → `${tm.data.dir}/audio/`
   - team photos → `${tm.data.dir}/photos/`
   - certificate templates → `${tm.data.dir}/certificate-templates/`
   - slotopt worker keys → `${tm.data.dir}/slotopt-keys/`
   - info-portal keypair → `${tm.data.dir}/info-portal-keys/`

3. **Per-purpose override env vars are retained as optional escape hatches.** `TM_DB_PATH`, `TM_AUDIT_LOG_DATA_DIR`, `TM_AUDIO_DATA_DIR`, `TM_PHOTOS_DATA_DIR`, `TM_CERT_TEMPLATES_DATA_DIR`, `TM_SLOTOPT_WORKER_KEY_DIR`, `INFO_PORTAL_KEYPAIR_DIR` continue to exist — an operator can still relocate one individual path (e.g. photos to a large disk). Only the **default** of each derives from `${tm.data.dir}`; no per-purpose path carries an independent default root any more. Trade-off acknowledged: because the per-purpose overrides remain, an operator *can* still manually re-fragment the layout (e.g. by setting `TM_AUDIT_LOG_DATA_DIR` back to a different root). DEC-68 is therefore a **default-convergence** guarantee — one root out of the box, one backup target by default — not a hard structural lock; the escape hatches are intentionally preserved over enforced single-rootedness.

4. **Derive-don't-add (forward-binding rule).** Any future feature that needs a filesystem path MUST derive its default from `${tm.data.dir}`. Introducing a new independent default root is forbidden. A story that adds a path-needing feature cites DEC-68 in `related_decs` and states the derived `${tm.data.dir}/...` path in an acceptance criterion. This is the clause that prevents the split (§ Context) from re-accruing.

5. **On-disk sub-structure is unchanged.** DEC-68 unifies only the ROOT. The internal layout under each path (`tenants/{uuid}/`, `audio/{tournamentId}/`, `audit-log/{tournament-id}/`, etc.) is untouched.

## Impact

- Operationalized by story **E55S15**. The change has four parts: (a) `application.yml` rework — introduce `tm.data.dir`, rewrite the seven per-purpose path defaults (datasource URL, audit-log, audio, photos, certificate-templates, slotopt worker-keys, info-portal keypair) to derive `${tm.data.dir}/...` via Spring property-placeholder nesting; (b) two Java config-bean field defaults re-rooted `~/.vvwt-tm` → `~/.tournament-manager` (`TmDataDirProperties`, `AuditLogConfig`) — these are belt-and-suspenders fallbacks for an absent `application.yml`; (c) `DatabaseDirectoryInitializer.resolveDatasourceUrl()` — an `EnvironmentPostProcessor` whose fallback branch hardcodes the DB root; re-rooted to honour `TM_DATA_DIR` (the bound `PhotoStorageConfig` / `AudioStorageConfig` beans, by contrast, carry no Java-level default literal and need no code change); (d) the jlink launcher scripts (`tournament-manager`, `tournament-manager.bat`) — expose `TM_DATA_DIR` as the single override knob in place of the hardcoded `TM_DB_PATH` default. Parts (b) and (c) are first-party code → DEC-22 RED-first applies to them.
- **DEC-14** (H2 persistence): the bootstrap and per-tenant DB file paths are now canonically rooted under `tm.data.dir`. No DEC-14 clause changes — DEC-14 fixed the engine and schema, DEC-68 fixes where the files live.
- **DEC-20** (DB-per-Tenant): complemented, not amended. DEC-20 already used the `${tm.data.dir}` variable and deferred the concrete path; DEC-68 fixes that canon. The `tenants/{uuid}/` sub-layout from DEC-20 is preserved verbatim.
- **DEC-15** (self-host deployment): directly served — one operator-facing data directory, one backup target (`cp -r ~/.tournament-manager`), one environment variable.
- **DEC-25** (§no-production-data): the re-rooting needs no data backfill — the operator deletes the old `~/.vvwt-tm` tree before rollout. With root `~/.tournament-manager`, the bootstrap DB + audio + photos + certificate-templates + slotopt-keys do not move; only the per-tenant DBs and audit log (currently under `~/.vvwt-tm`) move.
- Future path-needing stories cite DEC-68 and derive their path — the derive-don't-add rule (clause 4) is the enforcement.
- **No supersession** — DEC-68 is a new architectural decision establishing a canon DEC-20 left open; it does not supersede or amend any DEC clause.
