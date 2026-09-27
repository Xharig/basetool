CREATE TABLE exchange_journal (
    id               UUID PRIMARY KEY,
    user_id          UUID                     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    client_id        VARCHAR(64)              NOT NULL,
    installation_key VARCHAR(64)              NOT NULL,
    batch_id         UUID                     NOT NULL,
    resource         VARCHAR(16)              NOT NULL,
    entity_key       VARCHAR(255)             NOT NULL,
    action           VARCHAR(24)              NOT NULL,
    removal          BOOLEAN                  NOT NULL,
    before_state     TEXT,
    after_state      TEXT,
    tx               BIGINT                   NOT NULL DEFAULT (pg_current_xact_id()::text)::bigint,
    recorded_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    undone_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_exchange_journal_resource CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP')),
    CONSTRAINT ck_exchange_journal_action CHECK (action IN ('BLUEPRINT_ADD', 'BLUEPRINT_REMOVE',
        'STOCK_SET_QUANTITY', 'SHIP_LINK', 'SHIP_UPSERT', 'SHIP_REMOVE'))
);

CREATE INDEX idx_exchange_journal_member_client
    ON exchange_journal (user_id, client_id, resource, recorded_at);
CREATE INDEX idx_exchange_journal_recorded_at ON exchange_journal (recorded_at);
