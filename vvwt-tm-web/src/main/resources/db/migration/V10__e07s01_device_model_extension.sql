-- ============================================================
-- V10__e07s01_device_model_extension.sql — Device model extension for display devices
-- Story:  E07S01
-- DECs:   DEC-5  (devices scoped to tenant and location)
--         DEC-14 (H2 + Flyway persistence; configuration as TEXT for H2 compatibility)
--         DEC-17 (eager schema; new columns nullable — display devices have no PIN)
-- ============================================================
--
-- Extends the `devices` table to support display devices alongside scoring tablets.
--
-- Changes:
--   1. Make `pin` nullable — display devices register via URL, not PIN (E07S01 AC1).
--      The existing UNIQUE constraint on (tenant_id, pin) is RETAINED: per the SQL standard
--      (ISO/IEC 9075) and H2's implementation, NULL values in a UNIQUE constraint are
--      treated as distinct (NULLs are not equal to each other). Therefore, two display
--      devices with pin = NULL in the same tenant do NOT violate the constraint. Non-null
--      PINs for scoring tablets continue to be unique-within-tenant at the DB level (AC3).
--   2. Add `device_name` (VARCHAR 255, nullable) — human-readable label for display devices
--   3. Add `configuration` (TEXT, nullable) — JSON configuration per DEC-14 (H2 TEXT type
--      for compatibility; no database-specific JSON type).
-- ============================================================

-- 1. Make pin nullable (ALTER COLUMN in H2 syntax).
--    The UNIQUE constraint uq_devices_tenant_pin is NOT dropped — null-null uniqueness
--    in H2 follows SQL standard (NULLs are distinct), so multiple NULL-pin rows are valid.
ALTER TABLE devices ALTER COLUMN pin VARCHAR(8) NULL;

-- 2. Add device_name column
ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_name VARCHAR(255);

-- 3. Add configuration column (TEXT for H2 compatibility per DEC-14)
ALTER TABLE devices ADD COLUMN IF NOT EXISTS configuration TEXT;
