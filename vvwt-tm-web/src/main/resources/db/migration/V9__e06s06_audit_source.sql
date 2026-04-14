-- ============================================================
-- V9__e06s06_audit_source.sql — Add source columns to audit_log
-- Story:  E06S06
-- AC:     AC7, AC8
-- DECs:   DEC-14 (H2 + Flyway persistence; audit_log table)
--         DEC-19 (scoring tablet; device token as source identifier)
-- ============================================================
--
-- Adds two nullable columns to audit_log to distinguish the source
-- of each score entry or correction (AC8):
--
--   source_type       — 'TABLET' | 'ADMIN' | NULL (legacy rows)
--   source_device_id  — UUID string of the device (when source_type = 'TABLET')
--                       NULL when source_type = 'ADMIN' or legacy row
--
-- Additive migration only — existing rows retain NULL in both columns.
-- No rows are inserted per DEC-17.
-- ============================================================

ALTER TABLE audit_log ADD COLUMN source_type     VARCHAR(32)  NULL;
ALTER TABLE audit_log ADD COLUMN source_device_id VARCHAR(128) NULL;

-- Fast lookup: "show all tablet entries for this match" (AC8)
CREATE INDEX idx_audit_log_match_source ON audit_log (match_id, source_type);
