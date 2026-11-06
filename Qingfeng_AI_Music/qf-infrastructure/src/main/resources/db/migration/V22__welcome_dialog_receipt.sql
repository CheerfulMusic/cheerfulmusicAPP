CREATE TABLE welcome_dialog_receipt (
 creator_id VARCHAR(36) NOT NULL PRIMARY KEY,
 shown_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_welcome_dialog_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
