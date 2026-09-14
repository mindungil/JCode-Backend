ALTER TABLE course
    ADD COLUMN workspace_policy_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN workspace_policy_initialized BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE assignment
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN finalization_generation INT NOT NULL DEFAULT 0,
    ADD COLUMN starter_distribution_pending BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE user_courses
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE assignment
    MODIFY COLUMN description VARCHAR(500) NULL;

ALTER TABLE jcode
    ADD COLUMN desired_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN observed_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN desired_mount_hash VARCHAR(64) NULL,
    ADD COLUMN observed_mount_hash VARCHAR(64) NULL,
    ADD COLUMN last_routed_at DATETIME(6) NULL,
    ADD COLUMN expires_at DATETIME(6) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE workspace_operation
    ADD COLUMN desired_revision BIGINT NULL,
    ADD INDEX ix_workspace_operation_revision (target_type, target_id, action, desired_revision, status);

ALTER TABLE starter_artifact
    ADD COLUMN distribution_started_at DATETIME(6) NULL,
    ADD COLUMN published_at DATETIME(6) NULL,
    ADD INDEX ix_starter_artifact_published (assignment_id, published_at, version);

-- Before V12, READY meant only that the ZIP was stored. Publish an existing artifact
-- only when its distribution operation actually completed; deployNow=false and failed
-- distributions must not leak into later membership provisioning or restoration.
UPDATE starter_artifact artifact
JOIN workspace_operation operation
  ON operation.artifact_id = artifact.id
 AND operation.target_type = 'ASSIGNMENT'
 AND operation.action = 'DISTRIBUTE_STARTER'
 AND operation.status = 'SUCCEEDED'
SET artifact.distribution_started_at = artifact.uploaded_at,
    artifact.published_at = artifact.uploaded_at
WHERE artifact.status = 'READY';

UPDATE assignment
SET finalization_generation = 1
WHERE finalized_at IS NOT NULL;

CREATE INDEX ix_jcode_course_policy
    ON jcode (course_id, lifecycle_status, desired_revision, observed_revision);
