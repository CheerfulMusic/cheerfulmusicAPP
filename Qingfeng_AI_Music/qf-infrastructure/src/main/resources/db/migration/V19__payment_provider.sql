ALTER TABLE payment_order ADD COLUMN provider VARCHAR(24) NOT NULL DEFAULT 'AIRWALLEX';
ALTER TABLE payment_order ADD COLUMN provider_capture_id VARCHAR(128);
ALTER TABLE payment_order ADD CONSTRAINT uq_payment_capture UNIQUE (provider_capture_id);
