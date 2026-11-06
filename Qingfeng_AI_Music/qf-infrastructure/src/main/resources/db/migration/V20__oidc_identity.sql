CREATE TABLE creator_oidc_identity (
    subject_hash CHAR(64) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    email VARCHAR(320),
    phone_masked VARCHAR(40),
    display_name VARCHAR(200),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_oidc_creator UNIQUE(creator_id),
    CONSTRAINT fk_oidc_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
