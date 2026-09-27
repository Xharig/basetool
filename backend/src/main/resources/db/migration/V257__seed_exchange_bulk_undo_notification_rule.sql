INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000000e',
        'EXCHANGE_BULK_UNDO_APPLIED',
        'EXCHANGE_BULK_UNDO_APPLIED',
        'Default: notify the member when an admin undid a connected application''s changes to '
            'their data, so a change they did not make is explained (REQ-XCH-034).',
        TRUE,
        FALSE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000000e', 'EVENT_RECIPIENT');
