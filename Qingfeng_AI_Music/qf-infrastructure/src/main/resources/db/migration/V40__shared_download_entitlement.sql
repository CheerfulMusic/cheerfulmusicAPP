CREATE TABLE shared_download_entitlement (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    work_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shared_download_owner_asset UNIQUE (owner_id, work_id, asset_id),
    CONSTRAINT fk_shared_download_owner FOREIGN KEY (owner_id) REFERENCES creator(id),
    CONSTRAINT fk_shared_download_work FOREIGN KEY (work_id) REFERENCES music_work(id),
    CONSTRAINT fk_shared_download_asset FOREIGN KEY (asset_id) REFERENCES media_asset(id)
);
