-- ============================================================
-- infoportal/V1__initial_schema.sql — InfoPortal context schema
-- Epic:  E45 (Wave-2 Big-Bang-Reset)
-- Story: E45S05
-- DECs:  DEC-20 (DB-per-Tenant: info_portal_state in per-tenant H2 file)
--        DEC-25 (Wave-2 Big-Bang-Reset: single atomic commit, reconstructed-from-scratch)
--        DEC-39 D1 (tenant_id removed from tenant-scoped tables)
--        DEC-50 (amendment to DEC-39 D1: info_portal_state is the 17th table;
--                PK rewritten from (tenant_id, location_id, tournament_id)
--                to (location_id, tournament_id); tenant_id column dropped)
--        DEC-42 (TM publisher state lives in per-tenant H2 per DEC-20)
--        DEC-26 + DEC-46 (DEC-26 three-rule DAO test governance scope)
-- ============================================================
--
-- Replaces root V17__e38s09_info_portal_state.sql.
-- Reconstructed from scratch per DEC-25 §Decision consolidation-vs-mutation invariant.
--
-- Key differences from root V17:
--   - tenant_id column DROPPED (DEC-50 amending DEC-39 D1)
--   - PK rewritten from (tenant_id, location_id, tournament_id)
--                     to (location_id, tournament_id)
--   - Type preservation: VARCHAR(255) for surviving PK columns
--     (DEC-39 UUID vocabulary is informative; type-coercion N/A per DEC-50)
--   - All other columns preserved unchanged
-- ============================================================

-- E38S09 / DEC-42 / DEC-50: per-tournament publisher state for the Public Participant Info Portal.
-- Rows in this per-tenant H2 file implicitly belong to that file's tenant (DEC-39 D4).
-- DEC-26 Rule 1: production migration is the schema source of truth for DAO tests.
-- DEC-46: extends DEC-26 three-rule scope to this table.
CREATE TABLE info_portal_state (
    -- DEC-50: tenant_id column DROPPED; PK = (location_id, tournament_id)
    location_id           VARCHAR(255)  NOT NULL,
    tournament_id         VARCHAR(255)  NOT NULL,
    last_published_seq    BIGINT        NOT NULL DEFAULT 0,
    tournament_token      VARCHAR(1024) NOT NULL,
    per_tournament_secret VARBINARY(64) NOT NULL,
    last_published_at     TIMESTAMP WITH TIME ZONE,
    registration_status   VARCHAR(32)   NOT NULL DEFAULT 'REGISTERED',
    CONSTRAINT pk_info_portal_state PRIMARY KEY (location_id, tournament_id)
);
