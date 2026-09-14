ALTER TABLE course
    ADD COLUMN creation_request_key VARCHAR(64) NULL,
    ADD COLUMN creation_request_hash VARCHAR(64) NULL,
    ADD UNIQUE INDEX uk_course_creation_request (creation_request_key);
