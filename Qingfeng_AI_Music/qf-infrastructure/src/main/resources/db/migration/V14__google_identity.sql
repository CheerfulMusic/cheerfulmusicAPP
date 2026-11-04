CREATE TABLE creator_google_identity (
    subject_hash CHAR(64) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    email VARCHAR(320),
    display_name VARCHAR(200),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_google_creator UNIQUE(creator_id),
    CONSTRAINT fk_google_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
