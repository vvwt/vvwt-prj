-- Flyway migration V5 — result.late_result table
-- Story: E37S09
-- DEC-26 (analogously applied): this is the single schema source of truth for LateResultRepositoryIT
-- (DEC-26 Rule 1: schema-from-migration).
-- DEC-6 first-valid-wins: late results are logged, not discarded.
-- signature stored as VARBINARY(8192) per C-19 to accommodate future PQC signature sizes.

CREATE TABLE late_result (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    packet_id           UUID                     NOT NULL,
    worker_id           UUID                     NOT NULL,
    algorithm           VARCHAR(32)              NOT NULL,
    signature           VARBINARY(8192)          NOT NULL,
    result_payload_json VARCHAR(65536)           NOT NULL,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
