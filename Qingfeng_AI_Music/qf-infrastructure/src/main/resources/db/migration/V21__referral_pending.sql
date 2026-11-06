CREATE TABLE referral_pending (
    invitee_id VARCHAR(36) PRIMARY KEY,
    inviter_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_referral_invitee FOREIGN KEY (invitee_id) REFERENCES creator(id),
    CONSTRAINT fk_referral_inviter FOREIGN KEY (inviter_id) REFERENCES creator(id)
);
CREATE INDEX idx_referral_inviter ON referral_pending(inviter_id);
