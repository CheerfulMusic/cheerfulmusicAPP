-- Only for the empty, identity-checked local capacity-test schema.
UPDATE p0_credit_cutover_state
SET status='ACTIVE', activated_at=CURRENT_TIMESTAMP, evidence='EMPTY_CAPACITY_TEST_DATABASE'
WHERE id=1 AND status='PREPARING'
  AND NOT EXISTS (SELECT 1 FROM creator)
  AND NOT EXISTS (SELECT 1 FROM credit_ledger);
