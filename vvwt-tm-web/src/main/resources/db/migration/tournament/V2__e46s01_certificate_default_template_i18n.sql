-- ============================================================
-- tournament/V2__e46s01_certificate_default_template_i18n.sql — Tournament language + organizer columns
-- Epic:  E46 (Certificate i18n + organizer)
-- Story: E46S01
-- DECs:  DEC-20 (DB-per-Tenant: columns live in per-tenant H2 file)
--        DEC-25 (per-module Flyway layout: extends tournament/V1 schema)
--        DEC-39 D1 (tenant_id removed from tenant-scoped tables — unaffected here)
-- ============================================================
--
-- Adds three columns to the tournament-context tables:
--
--   tournament.organizer  VARCHAR(255) NULL
--     Snapshot of tenants.display_name captured at tournament INSERT time.
--     No DEFAULT clause — value is computed by application code (DefaultTournamentRepository.save()).
--     Existing rows survive migration with organizer = NULL (backward-compatible per T-3).
--
--   tournament.language   VARCHAR(8)  NULL
--     Per-tournament language override for the LocaleResolver chain (E46S02).
--     NULL semantics: fall through to tenants.language ?? default 'de'.
--     No DEFAULT clause.
--
--   team.language         VARCHAR(8)  NULL
--     Per-team language override for the LocaleResolver chain (E46S02).
--     NULL semantics: fall through to tournament.language ?? tenants.language ?? 'de'.
--     No DEFAULT clause.
--
-- No index, no FK, no check constraint on any of the three columns.
--
-- AC-MIGRATION-TOURNAMENT-V2-FILE-PRESENT
-- AC-MIGRATION-COLUMN-TOURNAMENT-ORGANIZER
-- AC-MIGRATION-COLUMN-TOURNAMENT-LANGUAGE
-- AC-MIGRATION-COLUMN-TEAM-LANGUAGE
-- AC-MIGRATION-NO-INDEX-NO-FK
-- AC-INSERT-NO-DEFAULT-CLAUSE
-- ============================================================

ALTER TABLE tournament ADD COLUMN organizer VARCHAR(255) NULL;
ALTER TABLE tournament ADD COLUMN language  VARCHAR(8)   NULL;
ALTER TABLE team       ADD COLUMN language  VARCHAR(8)   NULL;
