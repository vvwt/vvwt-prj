-- ============================================================
-- tenant/V2__e46s01_certificate_default_template_i18n.sql — Tenant language column
-- Epic:  E46 (Certificate i18n + organizer)
-- Story: E46S01
-- DECs:  DEC-20 (DB-per-Tenant: column lives in per-tenant H2 file)
--        DEC-25 (per-module Flyway layout: extends tenant/V1 schema)
--        DEC-39 D1 (tenant_id removed from tenant-scoped tables — unaffected here)
-- ============================================================
--
-- Adds the language carrier column to the tenants table.
-- NULL semantics: NULL = "fall through to default 'de'" in the resolver chain (E46S02).
-- No DEFAULT clause — application code sets the value on write; V1 leaves it NULL.
-- No index, no FK, no check constraint (pure data-carrier column).
--
-- AC-MIGRATION-TENANT-V2-FILE-PRESENT
-- AC-MIGRATION-COLUMN-TENANTS-LANGUAGE
-- AC-MIGRATION-NO-INDEX-NO-FK
-- ============================================================

ALTER TABLE tenants ADD COLUMN language VARCHAR(8) NULL;
