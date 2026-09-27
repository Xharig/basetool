CREATE TABLE exchange_ship_link (
    id               UUID PRIMARY KEY,
    user_id          UUID                     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    client_id        VARCHAR(64)              NOT NULL,
    installation_key VARCHAR(64)              NOT NULL,
    external_id      VARCHAR(128)             NOT NULL,
    ship_id          UUID                     NOT NULL REFERENCES ship (id) ON DELETE CASCADE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uk_exchange_ship_link_external
        UNIQUE (user_id, client_id, installation_key, external_id),
    CONSTRAINT uk_exchange_ship_link_ship UNIQUE (user_id, client_id, installation_key, ship_id)
);

CREATE INDEX idx_exchange_ship_link_ship ON exchange_ship_link (ship_id);
