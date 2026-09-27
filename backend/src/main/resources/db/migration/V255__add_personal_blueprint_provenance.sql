ALTER TABLE personal_blueprint
    ADD COLUMN source           VARCHAR(16),
    ADD COLUMN source_client_id VARCHAR(64),
    ADD CONSTRAINT chk_personal_blueprint_source
        CHECK (source IS NULL OR source IN ('LOG', 'MANUAL', 'IMPORT', 'DEFAULT', 'OTHER'));

UPDATE personal_blueprint pb
SET source = 'DEFAULT'
FROM default_blueprint d
WHERE d.product_key = pb.product_key;
