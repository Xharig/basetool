CREATE TABLE exchange_bulk_undo_run (
    id                 UUID PRIMARY KEY,
    exchange_client_id UUID                     NOT NULL REFERENCES exchange_client (id) ON DELETE CASCADE,
    client_id          VARCHAR(64)              NOT NULL,
    installation_id    UUID                     REFERENCES exchange_installation (id) ON DELETE SET NULL,
    installation_key   VARCHAR(64),
    resource           VARCHAR(16),
    since              TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_by       UUID                     REFERENCES app_user (id) ON DELETE SET NULL,
    status             VARCHAR(16)              NOT NULL,
    members_total      INTEGER                  NOT NULL,
    members_done       INTEGER                  NOT NULL DEFAULT 0,
    members_failed     INTEGER                  NOT NULL DEFAULT 0,
    restored           INTEGER                  NOT NULL DEFAULT 0,
    skipped            INTEGER                  NOT NULL DEFAULT 0,
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_exchange_bulk_undo_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_exchange_bulk_undo_run_resource
        CHECK (resource IS NULL OR resource IN ('BLUEPRINT', 'STOCK', 'SHIP'))
);

CREATE UNIQUE INDEX uq_exchange_bulk_undo_run_running
    ON exchange_bulk_undo_run (exchange_client_id) WHERE status = 'RUNNING';
CREATE INDEX idx_exchange_bulk_undo_run_client ON exchange_bulk_undo_run (exchange_client_id);
CREATE INDEX idx_exchange_bulk_undo_run_started_at ON exchange_bulk_undo_run (started_at);
CREATE INDEX idx_exchange_bulk_undo_run_requested_by ON exchange_bulk_undo_run (requested_by);
CREATE INDEX idx_exchange_bulk_undo_run_installation ON exchange_bulk_undo_run (installation_id);

CREATE TABLE exchange_bulk_undo_skip (
    id               UUID PRIMARY KEY,
    run_id           UUID        NOT NULL REFERENCES exchange_bulk_undo_run (id) ON DELETE CASCADE,
    user_id          UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    journal_entry_id UUID,
    resource         VARCHAR(16),
    reason           VARCHAR(24) NOT NULL,
    CONSTRAINT ck_exchange_bulk_undo_skip_reason
        CHECK (reason IN ('CHANGED_AFTERWARDS', 'GONE', 'FAILED')),
    CONSTRAINT ck_exchange_bulk_undo_skip_resource
        CHECK (resource IS NULL OR resource IN ('BLUEPRINT', 'STOCK', 'SHIP'))
);

CREATE INDEX idx_exchange_bulk_undo_skip_run ON exchange_bulk_undo_skip (run_id);
CREATE INDEX idx_exchange_bulk_undo_skip_user ON exchange_bulk_undo_skip (user_id);

CREATE INDEX idx_exchange_journal_client_recorded ON exchange_journal (client_id, recorded_at);
