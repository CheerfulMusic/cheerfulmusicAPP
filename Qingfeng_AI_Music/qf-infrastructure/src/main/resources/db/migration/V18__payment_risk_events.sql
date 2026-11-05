CREATE TABLE payment_risk_event (
  provider VARCHAR(24) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (provider, event_id),
  CONSTRAINT fk_risk_event_order FOREIGN KEY (order_id) REFERENCES payment_order(id)
);
