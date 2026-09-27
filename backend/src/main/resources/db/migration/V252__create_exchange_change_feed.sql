CREATE TABLE exchange_change (
    seq            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        UUID                     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    resource       VARCHAR(16)              NOT NULL,
    entity_key     VARCHAR(255)             NOT NULL,
    source_channel VARCHAR(8)               NOT NULL,
    source_client  VARCHAR(64),
    source_key     VARCHAR(64),
    changed_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_exchange_change_resource CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP')),
    CONSTRAINT ck_exchange_change_channel CHECK (source_channel IN ('web', 'app', 'client', 'system'))
);

CREATE INDEX idx_exchange_change_user_id ON exchange_change (user_id, resource, seq);
CREATE INDEX idx_exchange_change_changed_at ON exchange_change (changed_at);

CREATE TABLE exchange_feed_horizon (
    id                 SMALLINT PRIMARY KEY,
    purged_through_seq BIGINT                   NOT NULL,
    purged_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_exchange_feed_horizon_singleton CHECK (id = 1)
);

INSERT INTO exchange_feed_horizon (id, purged_through_seq) VALUES (1, 0);

CREATE OR REPLACE FUNCTION exchange_record_change(p_user UUID, p_resource VARCHAR, p_key VARCHAR)
RETURNS VOID AS $$
DECLARE
    v_source  TEXT := COALESCE(NULLIF(current_setting('basetool.change_source', true), ''), 'system');
    v_channel TEXT := split_part(v_source, '|', 1);
BEGIN
    IF p_user IS NULL OR p_key IS NULL
       OR NOT EXISTS (SELECT 1 FROM app_user WHERE id = p_user) THEN
        RETURN;
    END IF;
    IF v_channel NOT IN ('web', 'app', 'client', 'system') THEN
        v_channel := 'system';
    END IF;
    INSERT INTO exchange_change
        (user_id, resource, entity_key, source_channel, source_client, source_key)
    VALUES (p_user, p_resource, p_key, v_channel,
            CASE WHEN v_channel = 'client'
                 THEN NULLIF(left(split_part(v_source, '|', 2), 64), '') END,
            CASE WHEN v_channel = 'client'
                 THEN NULLIF(left(split_part(v_source, '|', 3), 64), '') END);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_personal_blueprint_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM exchange_record_change(OLD.owner_user_id, 'BLUEPRINT', OLD.product_key);
    END IF;
    IF TG_OP = 'INSERT'
       OR (TG_OP = 'UPDATE'
           AND (NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
                OR NEW.product_key IS DISTINCT FROM OLD.product_key)) THEN
        PERFORM exchange_record_change(NEW.owner_user_id, 'BLUEPRINT', NEW.product_key);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_personal_blueprint_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON personal_blueprint
FOR EACH ROW EXECUTE FUNCTION exchange_personal_blueprint_changed();

CREATE OR REPLACE FUNCTION exchange_default_blueprint_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM exchange_record_change(b.owner_user_id, 'BLUEPRINT', b.product_key)
           FROM personal_blueprint b
          WHERE b.product_key = OLD.product_key;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM exchange_record_change(b.owner_user_id, 'BLUEPRINT', b.product_key)
           FROM personal_blueprint b
          WHERE b.product_key = NEW.product_key;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_default_blueprint_exchange_change
AFTER INSERT OR DELETE OR UPDATE OF product_key ON default_blueprint
FOR EACH ROW EXECUTE FUNCTION exchange_default_blueprint_changed();

CREATE OR REPLACE FUNCTION exchange_stock_lot_key(
    p_material UUID, p_item UUID, p_location UUID, p_quality INTEGER, p_stolen BOOLEAN)
RETURNS VARCHAR AS $$
    SELECT CASE WHEN p_material IS NOT NULL THEN 'm:' || p_material ELSE 'i:' || p_item END
           || '|l:' || p_location
           || '|q:' || COALESCE(p_quality, 0)
           || '|s:' || CASE WHEN p_stolen THEN '1' ELSE '0' END;
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION exchange_inventory_item_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.personal THEN
        PERFORM exchange_record_change(OLD.user_id, 'STOCK',
            exchange_stock_lot_key(OLD.material_id, OLD.game_item_id, OLD.location_id,
                                   OLD.quality, OLD.stolen));
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.personal
       AND (TG_OP = 'INSERT'
            OR NOT OLD.personal
            OR NEW.user_id IS DISTINCT FROM OLD.user_id
            OR NEW.material_id IS DISTINCT FROM OLD.material_id
            OR NEW.game_item_id IS DISTINCT FROM OLD.game_item_id
            OR NEW.location_id IS DISTINCT FROM OLD.location_id
            OR NEW.quality IS DISTINCT FROM OLD.quality
            OR NEW.stolen IS DISTINCT FROM OLD.stolen) THEN
        PERFORM exchange_record_change(NEW.user_id, 'STOCK',
            exchange_stock_lot_key(NEW.material_id, NEW.game_item_id, NEW.location_id,
                                   NEW.quality, NEW.stolen));
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_item_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON inventory_item
FOR EACH ROW EXECUTE FUNCTION exchange_inventory_item_changed();

CREATE OR REPLACE FUNCTION exchange_ship_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM exchange_record_change(OLD.owner_id, 'SHIP', OLD.id::text);
    END IF;
    IF TG_OP = 'INSERT'
       OR (TG_OP = 'UPDATE' AND NEW.owner_id IS DISTINCT FROM OLD.owner_id) THEN
        PERFORM exchange_record_change(NEW.owner_id, 'SHIP', NEW.id::text);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ship_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON ship
FOR EACH ROW EXECUTE FUNCTION exchange_ship_changed();
