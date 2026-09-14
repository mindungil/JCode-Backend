-- Archived instances remain as history. A duplicate active instance must be
-- resolved before upgrading; the unique index intentionally fails closed.
ALTER TABLE jcode
    ADD COLUMN active_instance_key VARCHAR(96)
        GENERATED ALWAYS AS (
            CASE WHEN lifecycle_status <> 'ARCHIVED' AND kind IN ('STANDARD', 'SNAPSHOT')
                 THEN instance_key ELSE NULL END
        ) VIRTUAL,
    ADD UNIQUE INDEX uk_jcode_active_instance (active_instance_key);
