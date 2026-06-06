-- ============================================================
-- certificate/V2__add_photo_aspect_ratio.sql
-- Story:  E71S02 (per-certificate-template photo aspect ratio override)
-- DECs:   DEC-14 (H2+Flyway), DEC-20 (DB-per-Tenant), DEC-21 (per-module migration path)
-- ============================================================
--
-- Adds two nullable integer columns to certificate_template:
--   photo_aspect_ratio_width  — width component of the optional crop ratio override
--   photo_aspect_ratio_height — height component of the optional crop ratio override
--
-- Both columns are nullable. NULL means "no override; use global default from tm.photos".
-- Both must be positive when set (enforced at the service layer — E71S02 AC3).
--
-- Storage format: two separate integers (not a "W:H" string) to avoid parse complexity
-- and to be directly usable by the frontend crop step.
--
-- H2-compatible: each ADD COLUMN in a separate ALTER TABLE statement.
-- Uses IF NOT EXISTS as a defensive guard for the Big-Bang-Reset transition (DEC-25).

ALTER TABLE certificate_template
    ADD COLUMN IF NOT EXISTS photo_aspect_ratio_width INT;

ALTER TABLE certificate_template
    ADD COLUMN IF NOT EXISTS photo_aspect_ratio_height INT;
