-- Flyway migration V6 — result.result_audit_entry table
-- Story: E37S09
-- DEC-26 (analogously applied): this is the single schema source of truth for ResultAuditRepositoryIT
-- (DEC-26 Rule 1: schema-from-migration).
-- DEC-6: audit log with source IP and timestamp for every submit-result call.
-- outcome values: ACCEPTED, SUPERSEDED, SIGNATURE_INVALID (per AC-RESULT-AUDIT).

CREATE TABLE result_audit_entry (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    packet_id   UUID                     NOT NULL,
    worker_id   UUID                     NOT NULL,
    algorithm   VARCHAR(32)              NOT NULL,
    source_ip   VARCHAR(45)              NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome     VARCHAR(32)              NOT NULL
);
