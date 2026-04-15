-- E12S04: Certificate template metadata table
-- AC1: stores template metadata (filename, format, upload timestamp, file size) in H2
-- AC8: one template per tournament (tournament_id is the primary key)
-- DEC-14: H2 embedded + CRUD; no event sourcing
-- DEC-17: tournament_id FK references tournament table (tenant isolation via application layer)

CREATE TABLE certificate_template (
    tournament_id   UUID         NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    format          VARCHAR(10)  NOT NULL,   -- 'html' or 'svg'
    upload_timestamp TIMESTAMP   NOT NULL,
    file_size_bytes BIGINT       NOT NULL,
    CONSTRAINT pk_certificate_template PRIMARY KEY (tournament_id),
    CONSTRAINT fk_certificate_template_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
);
