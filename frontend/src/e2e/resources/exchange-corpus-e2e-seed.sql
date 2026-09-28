INSERT INTO blueprint (id, scwiki_uuid, scwiki_key, output_item_id, output_name, craft_time_seconds,
                       is_available_by_default, ingredient_count)
VALUES ('0e2e0000-0000-4000-8000-000000001701', '0e2e0000-0000-4000-8000-000000001801', NULL, NULL,
        'Monde Legs Delta Camo', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001702', '0e2e0000-0000-4000-8000-000000001802', NULL, NULL,
        'Monde Arms Hemlock Camo', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001703', '0e2e0000-0000-4000-8000-000000001803', NULL, NULL,
        'R97 Shotgun', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001704', '0e2e0000-0000-4000-8000-000000001804', NULL, NULL,
        'Strata Arms Levski Edition', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001705', '0e2e0000-0000-4000-8000-000000001805', NULL, NULL,
        'Strata Helmet Levski Edition', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001706', '0e2e0000-0000-4000-8000-000000001806', NULL, NULL,
        'Pitman Mining Laser', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001707', '0e2e0000-0000-4000-8000-000000001807', NULL, NULL,
        'CF-337 Panther "Hazard-Zone" Repeater', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001708', '0e2e0000-0000-4000-8000-000000001808', NULL, NULL,
        'CQ7 Rifle', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001709', '0e2e0000-0000-4000-8000-000000001809', NULL, NULL,
        'Lynx Arms (Core)', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001710', '0e2e0000-0000-4000-8000-000000001810', NULL, NULL,
        'Oracle Helmet', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001711', '0e2e0000-0000-4000-8000-000000001811', NULL, NULL,
        'BUL-H4 Helmet', 120, false, 0),
       ('0e2e0000-0000-4000-8000-000000001712', '0e2e0000-0000-4000-8000-000000001812', NULL, NULL,
        'BUL-H4 Armor', 120, false, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO blueprint_external_alias (id, source_system, external_name, product_key, product_name)
VALUES ('0e2e0000-0000-4000-8000-000000001901', 'SCMDB', 'Lynx Arms', 'lynx arms (core)',
        'Lynx Arms (Core)')
ON CONFLICT DO NOTHING;
