CREATE TABLE exchange_client (
    id                  UUID PRIMARY KEY,
    client_id           VARCHAR(63)  NOT NULL,
    display_name        VARCHAR(100) NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    min_client_version  VARCHAR(32),
    contact_url         VARCHAR(500),
    requests_per_minute INTEGER,
    writes_per_day      INTEGER,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_exchange_client_client_id UNIQUE (client_id),
    CONSTRAINT ck_exchange_client_client_id CHECK (client_id ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    CONSTRAINT ck_exchange_client_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_exchange_client_requests_per_minute CHECK (requests_per_minute IS NULL OR requests_per_minute > 0),
    CONSTRAINT ck_exchange_client_writes_per_day CHECK (writes_per_day IS NULL OR writes_per_day > 0)
);

COMMENT ON TABLE exchange_client IS
    'The registry of approved third-party exchange clients (REQ-XCH-003, ADR-0217); managed by ADMIN, mirrored into Redis under exchange:*.';

CREATE TABLE exchange_client_capability (
    exchange_client_id UUID        NOT NULL REFERENCES exchange_client (id) ON DELETE CASCADE,
    capability         VARCHAR(40) NOT NULL,
    PRIMARY KEY (exchange_client_id, capability),
    CONSTRAINT ck_exchange_client_capability CHECK (capability IN (
        'exchange.connect',
        'exchange.blueprints.read',
        'exchange.blueprints.write',
        'exchange.stock.read',
        'exchange.stock.write',
        'exchange.hangar.read',
        'exchange.hangar.write',
        'exchange.demand.read',
        'exchange.drafts.blueprints',
        'exchange.drafts.refinery'))
);

COMMENT ON TABLE exchange_client_capability IS
    'The capabilities granted to a registry client; the database alone decides them (REQ-XCH-004, ADR-0217).';

CREATE TABLE exchange_settings (
    id         SMALLINT PRIMARY KEY,
    enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_exchange_settings_singleton CHECK (id = 1)
);

COMMENT ON TABLE exchange_settings IS
    'The single row holding the global exchange switch (REQ-XCH-003); off until the go-live.';

INSERT INTO exchange_settings (id, enabled) VALUES (1, FALSE);

CREATE SEQUENCE exchange_registry_revision_seq;

COMMENT ON SEQUENCE exchange_registry_revision_seq IS
    'Numbers every write of the exchange registry mirror, so the gateway and the reconcile can tell writes apart (REQ-XCH-003).';
