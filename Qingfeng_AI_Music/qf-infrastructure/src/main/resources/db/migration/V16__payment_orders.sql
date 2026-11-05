CREATE TABLE payment_order (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  creator_id VARCHAR(36) NOT NULL,
  package_id VARCHAR(80) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  credits BIGINT NOT NULL,
  provider_link_id VARCHAR(128),
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_payment_link UNIQUE(provider_link_id),
  CONSTRAINT fk_payment_creator FOREIGN KEY (creator_id) REFERENCES creator(id)
);
