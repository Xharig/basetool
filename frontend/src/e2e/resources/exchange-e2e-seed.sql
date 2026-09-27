INSERT INTO material (id, version, created_at, updated_at, name, type, quantity_type, code,
                      id_commodity, refined_material_id, is_raw, is_refined, is_visible, scwiki_key)
VALUES ('0e2e0000-0000-4000-8000-000000001401', 0, now(), now(), 'E2E Exchange Metal', 'REFINED',
        'SCU', 'E2EXMET', NULL, NULL, 0, 1, true, 'e2e_exchange_metal')
ON CONFLICT (id) DO NOTHING;

INSERT INTO game_item (id, name, manufacturer_id, kind, source_systems, class_name, uex_item_id)
VALUES ('0e2e0000-0000-4000-8000-000000001501', 'E2E Exchange Rifle',
        '33333333-3333-3333-3333-333333333333', 'WEAPON', 'UEX_ONLY', 'e2em_exchange_rifle_01',
        990901)
ON CONFLICT (id) DO NOTHING;

INSERT INTO blueprint (id, scwiki_uuid, scwiki_key, output_item_id, output_name, craft_time_seconds,
                       is_available_by_default, ingredient_count)
VALUES ('0e2e0000-0000-4000-8000-000000001601', '0e2e0000-0000-4000-8000-000000001611',
        'BP_CRAFT_E2EM_EXCHANGE_RIFLE_01', '0e2e0000-0000-4000-8000-000000001501',
        'E2E Exchange Rifle', 120, false, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO blueprint_ingredient (id, blueprint_id, order_index, kind, material_id, quantity_scu,
                                  min_quality)
VALUES ('0e2e0000-0000-4000-8000-000000001621', '0e2e0000-0000-4000-8000-000000001601', 0,
        'RESOURCE', '0e2e0000-0000-4000-8000-000000001401', 0.5, NULL)
ON CONFLICT (id) DO NOTHING;
