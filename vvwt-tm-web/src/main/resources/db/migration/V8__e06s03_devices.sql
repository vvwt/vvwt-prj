-- ============================================================
-- V8__e06s03_devices.sql — Device registration table
-- Story:  E06S03
-- DECs:   DEC-5  (devices scoped to tenant and location)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager schema; tenant_id + location_id NOT NULL from day 1)
-- ============================================================
--
-- Creates the `devices` table for PIN-based scoring tablet registration.
--
-- A device is registered by a tablet opening a known URL. The server
-- generates a short numeric PIN (4–6 digits, unique within tenant) and
-- an opaque cryptographically random device token. The organizer uses
-- the PIN from the admin UI (E06S05) to assign the tablet to a court.
--
-- Columns:
--   id              UUID PK — application-generated
--   tenant_id       UUID NOT NULL FK tenants(id) — DEC-5, DEC-17
--   location_id     UUID NOT NULL FK locations(id) — DEC-5, DEC-17
--   device_token    VARCHAR UNIQUE NOT NULL — opaque token (UUID or hex); tablet auth
--   pin             VARCHAR NOT NULL — short numeric PIN for human assignment
--   device_type     VARCHAR NOT NULL DEFAULT 'SCORING_TABLET'
--   assigned_field  INT nullable — null = unassigned, non-null = court number
--   status          VARCHAR NOT NULL — 'REGISTERED' | 'ASSIGNED' | 'DISCONNECTED'
--   registered_at   TIMESTAMP NOT NULL — set at INSERT time
--   last_seen_at    TIMESTAMP nullable — updated by tablet heartbeat (future)
--
-- PIN uniqueness per tenant enforced at application level (DeviceService) plus
-- a partial constraint approach: a unique index on (tenant_id, pin) for active
-- pins (status != 'DISCONNECTED'). H2 does not support filtered/partial unique
-- indexes, so we enforce uniqueness application-level and add a plain unique
-- index on (tenant_id, pin) — a PIN may be reused only after explicit deletion
-- of the device row, not on disconnect.
-- ============================================================

CREATE TABLE devices (
    id              UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    location_id     UUID         NOT NULL,
    device_token    VARCHAR(128) NOT NULL,
    pin             VARCHAR(8)   NOT NULL,
    device_type     VARCHAR(32)  NOT NULL  DEFAULT 'SCORING_TABLET',
    assigned_field  INT,
    status          VARCHAR(32)  NOT NULL  DEFAULT 'REGISTERED',
    registered_at   TIMESTAMP    NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    last_seen_at    TIMESTAMP,
    CONSTRAINT pk_devices PRIMARY KEY (id),
    CONSTRAINT fk_devices_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_devices_location
        FOREIGN KEY (location_id) REFERENCES locations (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_devices_token
        UNIQUE (device_token),
    -- PIN unique within tenant: a device's PIN is reserved until the row is deleted
    CONSTRAINT uq_devices_tenant_pin
        UNIQUE (tenant_id, pin)
);

-- Fast lookup by device_token (AC2, AC3 — status poll by token)
CREATE INDEX idx_devices_token ON devices (device_token);

-- Fast lookup by tenant for admin listing and PIN search (AC4)
CREATE INDEX idx_devices_tenant_id ON devices (tenant_id);

-- Fast lookup by assigned_field within tenant+location (AC5 — conflict check)
CREATE INDEX idx_devices_tenant_location_field ON devices (tenant_id, location_id, assigned_field);
