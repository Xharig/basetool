INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000000d',
        'EXCHANGE_INSTALLATION_CONNECTED',
        'EXCHANGE_INSTALLATION_CONNECTED',
        'Default: notify the member when a new installation of an exchange client connects to '
            'their account, so a connection they did not make is noticed (REQ-XCH-032).',
        TRUE,
        FALSE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000000d', 'EVENT_RECIPIENT');
