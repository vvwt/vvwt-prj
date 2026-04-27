-- V3__consumed_invitation_tokens.sql (H2 dialect)
-- Story: E38S04 — AC10, DEC-42 D3, DEC-46
-- 6th table: consumed_invitation_tokens — tracks consumed invitation tokens for primary-profile
-- durability. On restart, the in-memory pool is rebuilt as:
--   config-list MINUS consumed_invitation_tokens contents (AC10).
--
-- Column length pin (AC10 cycle-2 F-R3 fix):
--   token_value VARCHAR(64) — accommodates Base64URL of 256-bit cryptographically random tokens
--   (43 chars padded; 64-char cap provides forward-headroom).

CREATE TABLE consumed_invitation_tokens (
    token_value    VARCHAR(64)  NOT NULL,
    consumed_at    TIMESTAMP    NOT NULL,
    consumed_by_tenant_id VARCHAR(128) NULL,
    CONSTRAINT pk_consumed_invitation_tokens PRIMARY KEY (token_value),
    CONSTRAINT fk_cit_tenant FOREIGN KEY (consumed_by_tenant_id)
        REFERENCES tenant (tenant_id)
);
