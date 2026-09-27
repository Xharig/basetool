ALTER TABLE inventory_item
    ADD COLUMN stolen BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN inventory_item.stolen IS
    'Whether this stock is marked „gestohlen“ (stolen cargo); part of the stock identity, so stolen and legitimate stock never share a stack or merge. REQ-INV-053.';

ALTER TABLE job_order_handover_item
    ADD COLUMN stolen BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN job_order_handover_item.stolen IS
    'Snapshot of the „gestohlen“ marker of the handed-over stock (REQ-INV-053).';

DROP INDEX IF EXISTS idx_inventory_item_stack_key;

CREATE INDEX idx_inventory_item_stack_key
    ON inventory_item (material_id, user_id, location_id, quality, personal, stolen, owning_org_unit_id);

COMMENT ON INDEX idx_inventory_item_stack_key IS
    'Composite index on the material stack identity (material, user, location, quality, personal, stolen, owning org unit). Backs the group-on-read GROUP BY and the per-stack entries lookup (REQ-INV-002, REQ-INV-053).';

DROP INDEX IF EXISTS idx_inventory_item_item_stack_key;

CREATE INDEX idx_inventory_item_item_stack_key
    ON inventory_item (game_item_id, user_id, location_id, personal, stolen, owning_org_unit_id)
    WHERE game_item_id IS NOT NULL;

COMMENT ON INDEX idx_inventory_item_item_stack_key IS
    'Partial composite index on the item stack identity (game item, user, location, personal, stolen, owning org unit) over game-item rows only (REQ-INV-029, REQ-INV-053).';
