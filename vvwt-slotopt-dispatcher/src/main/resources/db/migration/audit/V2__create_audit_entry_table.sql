-- Flyway migration V2 — audit.audit_entry table
-- Story: E37S06
-- DEC-6: audit log for identity/registration events (source IP, timestamp, event type).
-- DEC-26 (analogously applied): this is the single schema source of truth for AuditRepositoryIT
-- (DEC-26 Rule 1: schema-from-migration).
-- worker_id is nullable: some events have no worker context (e.g., early validation failures).

CREATE TABLE audit_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type    VARCHAR(64)              NOT NULL,
    worker_id     UUID,
    source_ip     VARCHAR(45)              NOT NULL,
    detail_json   VARCHAR(65536)
);
