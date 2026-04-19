-- ============================================================
-- V1__admin_credentials.sql — Admin credentials table (auth module)
-- Story:  E15S05 (relocated from root V6__e05s02_admin_credentials.sql)
-- DECs:   DEC-14 (H2+Flyway), DEC-20 (DB-per-Tenant), DEC-21 (per-module migration path)
-- ============================================================
--
-- Schema parity (AC8): This file is byte-identical in content to the legacy
-- V6__e05s02_admin_credentials.sql, which it replaces as the authoritative
-- source once E15S07 performs the atomic cutover (deletion of V1..V6 legacy files).
-- During this story both files coexist; only this per-module path is applied
-- by the PerTenantFlywayRunner (the legacy root is ignored per E14S04 AC8).
--
-- Security review (AC9): All credential-store invariants are preserved:
--   - password_hash VARCHAR(255): bcrypt hashes are 60 chars; 255 provides sufficient
--     headroom with no truncation risk. NOT NULL preserved.
--   - singleton_guard BOOLEAN NOT NULL DEFAULT TRUE + UNIQUE INDEX: single-row invariant
--     is preserved — at most one admin credential row may exist per tenant DB.
--   - CHECK (singleton_guard = TRUE): H2-compatible sentinel constraint, unchanged.
--   - No security-relevant constraint has been weakened or removed.
--
-- This migration creates the admin_credentials table.
-- The admin account is an instance-level concept for the LAN deployment
-- (one account per self-hosted instance — DEC-5 reasoning: the default-tenant
-- LAN path has one admin, not one admin per tenant).
--
-- Password storage: bcrypt hash (see E05S02 delivery decision).
-- The plaintext password is never stored in the database — only the hash.
-- The plaintext is displayed on the console at startup (AC3).
--
-- Single-row invariant: at most one admin credential row may exist.
-- Enforced via a single-row sentinel column and unique index
-- (same pattern as tenants.default_sentinel in V1__initial_schema.sql).
-- H2 2.3.232 does not support partial/filtered unique indexes; a sentinel
-- column with a unique index achieves the same guarantee.
--
-- AdminCredentialsBootstrap (ApplicationRunner @Order(2)) manages the row:
--   - First boot: INSERT with generated plaintext + bcrypt hash
--   - Subsequent boots: SELECT existing hash, display plaintext password
--     cannot be recovered from hash — only the INFO log line matters.
-- ============================================================

CREATE TABLE admin_credentials (
    id              UUID          NOT NULL,
    -- bcrypt-hashed password (cost 10). Plaintext displayed at startup only (AC3, AC11).
    password_hash   VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    -- Single-row sentinel: always TRUE (only one row may exist).
    -- A unique index on this column enforces the single-row invariant.
    -- The value is always TRUE — it is the PK surrogate for the uniqueness constraint.
    singleton_guard BOOLEAN       NOT NULL  DEFAULT TRUE,
    CONSTRAINT pk_admin_credentials PRIMARY KEY (id),
    CONSTRAINT chk_singleton_guard CHECK (singleton_guard = TRUE)
);

-- Enforce single-row invariant: at most one row with singleton_guard = TRUE.
-- TRUE values ARE considered equal in SQL UNIQUE constraints (unlike NULL),
-- so this index guarantees at most one row in the table.
CREATE UNIQUE INDEX idx_admin_credentials_singleton ON admin_credentials (singleton_guard);
