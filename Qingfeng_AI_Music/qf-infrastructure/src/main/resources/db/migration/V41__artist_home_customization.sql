ALTER TABLE artist_profile ADD (
    cover_key VARCHAR(36) NULL,
    cover_jpeg MEDIUMBLOB NULL,
    featured_release_id VARCHAR(36) NULL
);
