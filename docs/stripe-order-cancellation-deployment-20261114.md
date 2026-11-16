# Stripe order cancellation deployment — 2026-11-14

Status: PASS for deployment and technical verification. This is not four-channel live payment acceptance.

- Production source: `64d449768845b394ba936aa90c52e227ee404899`.
- JAR SHA-256: `6f8c9fb544af026b47c08936ca57fa6ec39ee6eec9122a918ad144e7106efa75`.
- Previous source: `66a933bbe8a65a209e9213b3fbe568a679031809`.
- Release: `/opt/qingfeng-music/releases/20261114-stripe-cancel-64d44976`.
- Backup and receipts: `/var/backups/qingfeng-music/20261114-stripe-cancel-64d44976`.

## Verification

Frontend: 786 passed. Backend Maven verify: 1524 tests, 1516 passed, 8 optional infrastructure tests skipped, zero failures/errors. Financial/MySQL tests were not skipped. Full application MySQL testing included concurrent cancellation and late payment idempotency.

Production preflight verified the exact previous artifact, configuration hash, migration chain V45 and writer protocol 3. The new artifact changes only the three Stripe backend classes and their inner classes, plus cancellation UI. No migration was executed. The unrelated V46 Agent changes on local main were excluded.

The full database backup includes 17 trigger definitions. A separate restore of base tables into a private verification schema matched all 77 tables and data fingerprints. Triggers were not executed in that schema, and no application ran against restored data. Production source fingerprints remained unchanged. This verifies base-table/data restoration, not a full trigger-enabled disaster recovery drill.

Service health UP; running JAR hash verified; public `/recharge` and `IdentityPage-C20A_uft.js` returned 200. Served asset contains cancellation endpoint and confirmation prompt. Backup timer restored to active.

## Scope and limits

Application configuration and purchase availability remain unchanged. No live order was cancelled as a test. WeChat live payment evidence from the preceding acceptance remains distinct from this release; credit card, Apple Pay and PayPal live acceptance must not be labelled PASS without actual evidence.

Local main contained this functionality before deployment (`308be0e`). Production uses the V45-compatible release above, not the complete main tree. No Git push was performed. Local running application version was not changed or verified in this deployment.

Rollback retains the previous release and policy backup. No database rollback is needed for this same-schema change. The operator automatically restores the old release if startup verification fails; an actual production rollback was not exercised because deployment succeeded.
