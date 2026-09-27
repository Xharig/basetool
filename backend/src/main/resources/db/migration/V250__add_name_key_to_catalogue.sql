ALTER TABLE game_item ADD COLUMN name_key VARCHAR(200);
ALTER TABLE material ADD COLUMN name_key VARCHAR(200);
ALTER TABLE ship_type ADD COLUMN name_key VARCHAR(200);

CREATE INDEX idx_game_item_name_key_lower ON game_item (lower(name_key));

COMMENT ON COLUMN game_item.name_key IS
    'The global.ini name key without the leading @, from the P4K import; resolves an exchange locKey (REQ-XCH-012).';
COMMENT ON COLUMN material.name_key IS
    'The global.ini name key without the leading @, from the P4K import; resolves an exchange locKey (REQ-XCH-012).';
COMMENT ON COLUMN ship_type.name_key IS
    'The global.ini name key without the leading @, from the P4K import; resolves an exchange locKey (REQ-XCH-012).';
