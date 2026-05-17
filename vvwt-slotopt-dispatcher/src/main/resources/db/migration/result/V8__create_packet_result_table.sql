-- Flyway migration V8 — result.packet_result table
-- Story: E60S02
-- DEC-26/DEC-46 (analogously applied): this is the single schema source of truth for
-- PacketResultRepositoryIT (DEC-26 Rule 1: schema-from-migration).
-- DEC-25: no production data exists, no data backfill required (AC-GOV-NO-BACKFILL).
-- DEC-9: only structural optimization data retained — no team UUIDs, names, or
--        identity-bearing attributes (AC-GOV-DEC9-STRUCTURAL-ONLY).
--
-- Each accepted packet result (worker bestRank / bestScore) is retained here,
-- keyed by packet_id (UNIQUE — first-valid-wins enforced at DB level) and associated
-- to the owning job via job_id for bulk-by-job queries (E60S03 aggregation substrate).

CREATE TABLE packet_result (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    packet_id   UUID   NOT NULL UNIQUE,
    job_id      UUID   NOT NULL,
    best_rank   INT    NOT NULL,
    best_score  DOUBLE NOT NULL
);

CREATE INDEX idx_packet_result_job_id ON packet_result (job_id);
