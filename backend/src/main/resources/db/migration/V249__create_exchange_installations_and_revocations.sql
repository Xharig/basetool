CREATE TABLE exchange_installation (
    id                 UUID PRIMARY KEY,
    exchange_client_id UUID        NOT NULL REFERENCES exchange_client (id) ON DELETE CASCADE,
    user_id            UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    key_thumbprint     VARCHAR(64) NOT NULL,
    label              VARCHAR(40),
    first_seen_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at         TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_exchange_installation_key UNIQUE (exchange_client_id, user_id, key_thumbprint),
    CONSTRAINT ck_exchange_installation_key_thumbprint CHECK (key_thumbprint ~ '^[A-Za-z0-9_-]{43}$')
);

CREATE INDEX idx_exchange_installation_user_id ON exchange_installation (user_id);

COMMENT ON TABLE exchange_installation IS
    'One external client on one PC, identified by its DPoP key thumbprint; a revoked row is the deny-list entry for that key (REQ-XCH-007, REQ-XCH-008).';

CREATE TABLE exchange_client_revocation (
    exchange_client_id UUID NOT NULL REFERENCES exchange_client (id) ON DELETE CASCADE,
    user_id            UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    revoked_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (exchange_client_id, user_id)
);

CREATE INDEX idx_exchange_client_revocation_user_id ON exchange_client_revocation (user_id);

COMMENT ON TABLE exchange_client_revocation IS
    'When a member last disconnected a whole client; a token issued before it is refused (REQ-XCH-008).';
