CREATE TABLE IF NOT EXISTS folder_metadata (
    is_deleted         TINYINT(1) DEFAULT 0 NOT NULL,
    created_at         TIMESTAMP NOT NULL,
    creator_id         BIGINT NULL,
    folder_metadata_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    folder_size        BIGINT DEFAULT 0 NOT NULL,
    owner_id           BIGINT NULL,
    parent_folder_id   BIGINT NULL,
    root_id            BIGINT NULL,
    sharing_expired_at TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL,
    upload_folder_name VARCHAR(100) NOT NULL,
    permission_type    VARCHAR(10) NOT NULL
    );

CREATE INDEX IF NOT EXISTS folder_idx_find_folder_with_cursor
    ON folder_metadata (parent_folder_id, is_deleted, folder_metadata_id);

CREATE INDEX IF NOT EXISTS folder_idx_parent_folder_id_created_at
    ON folder_metadata (parent_folder_id, created_at);

CREATE INDEX IF NOT EXISTS folder_idx_parent_folder_id_is_deleted
    ON folder_metadata (parent_folder_id, is_deleted);

CREATE INDEX IF NOT EXISTS folder_idx_parent_folder_id_size
    ON folder_metadata (parent_folder_id, folder_size);

CREATE TABLE IF NOT EXISTS file_metadata (
    created_at          TIMESTAMP NOT NULL,
    creator_id          BIGINT NOT NULL,
    file_metadata_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_size           BIGINT NOT NULL,
    owner_id            BIGINT NOT NULL,
    parent_folder_id    BIGINT NOT NULL,
    root_id             BIGINT NOT NULL,
    sharing_expired_at  TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    file_type           VARCHAR(50) NULL,
    thumbnail_file_name VARCHAR(100) NULL UNIQUE,
    upload_file_name    VARCHAR(100) NOT NULL,
    uuid_file_name      VARCHAR(100) NOT NULL UNIQUE,
    permission_type     VARCHAR(10) NOT NULL,
    upload_status       VARCHAR(30) NOT NULL
    );

CREATE INDEX IF NOT EXISTS file_idx_parent_folder_id_created_at
    ON file_metadata (parent_folder_id, file_size);

CREATE INDEX IF NOT EXISTS file_idx_parent_folder_id_file_metadata_id
    ON file_metadata (parent_folder_id, file_metadata_id);

CREATE INDEX IF NOT EXISTS file_idx_parent_folder_id_size
    ON file_metadata (parent_folder_id, created_at);

CREATE INDEX IF NOT EXISTS file_idx_upload_status
    ON file_metadata (upload_status);

CREATE TABLE IF NOT EXISTS users (
    root_folder_id BIGINT NOT NULL,
    user_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_name      VARCHAR(20) NOT NULL
);
