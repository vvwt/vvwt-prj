-- Flyway migration V1 — identity.key_registration table
-- Story: E37S05
-- DEC-26 (analogously applied): this is the single schema source of truth for the
-- KeyRegistrationRepositoryIT (DEC-26 Rule 1: schema-from-migration).
-- Schema per AC-KEY-REGISTRATION-ENTITY: single row per worker_id (C-12 lock from Brief).
-- VARBINARY(8192) per C-19 to accommodate future PQC public key sizes.

CREATE TABLE key_registration (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    worker_id     UUID            NOT NULL UNIQUE,
    algorithm     VARCHAR(32)     NOT NULL,
    public_key_bytes VARBINARY(8192) NOT NULL,
    role          VARCHAR(32)     NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL
);
