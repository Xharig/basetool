CREATE INDEX idx_exchange_change_user_key
    ON exchange_change (user_id, resource, entity_key, tx, seq);

COMMENT ON INDEX idx_exchange_change_user_key IS
    'The latest change of one key of a member (ExchangeChangeRepository.findLatestForKey), read once per op of a change set; idx_exchange_change_user_id cannot serve it because entity_key is not among its columns (REQ-XCH-013, REQ-XCH-016).';
