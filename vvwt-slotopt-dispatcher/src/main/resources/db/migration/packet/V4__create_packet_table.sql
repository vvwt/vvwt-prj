-- Flyway migration V4 — packet.packet table
-- Story: E37S08
-- DEC-26 (analogously applied): this is the single schema source of truth for the
-- PacketRepositoryIT (DEC-26 Rule 1: schema-from-migration).
-- Schema per AC-PACKET-RECORD-ENTITY: packet tracking for the dispatcher pull-packet endpoint.
-- status values: UNCLAIMED, CLAIMED, RESULT_RECEIVED, TIMEDOUT (per spec section (b)).

CREATE TABLE packet (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    packet_id            UUID                     NOT NULL UNIQUE,
    job_id               UUID                     NOT NULL,
    packet_payload_json  VARCHAR(65536)           NOT NULL,
    status               VARCHAR(32)              NOT NULL,
    claimed_by_worker_id UUID,
    claimed_at           TIMESTAMP WITH TIME ZONE,
    timeout_at           TIMESTAMP WITH TIME ZONE
);
