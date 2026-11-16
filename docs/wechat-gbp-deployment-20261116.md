# WeChat Pay GBP deployment — 2026-11-16

Deployment: PASS. Production source `a92bd0e88d8f66c0ea724c23b3d95bfe2fb57dce`; JAR SHA-256 `61337b320848472a3b38faafc90d0608fd5f0cbeedac3f768d12ba83fd5c3b78`.

GBP WeChat packages: 100 credits £7.99; 300 credits £19.99; 700 credits £39.99. CNY packages and existing USD card/Apple Pay/PayPal packages unchanged. Existing public purchase configuration preserved. No production migration or real payment performed.

Frontend: 787 passed, typecheck and production build passed. Final backend Maven verify: 1527 total, zero failures/errors; 8 optional infrastructure skips. Full application isolated MySQL: 33 passed, including GBP HTTP creation, exact server-owned price, duplicate signed success events, one credit entry, GBP partial refund with cumulative ceiling and duplicate refund idempotency. Earlier failed runs remain recorded: obsolete GBP-negative test and missing GBP registration in synthetic gateway fixture; corrected without deleting tests or relaxing actual merchant-country validation.

Exact tested production classes and frontend files matched the fresh committed-source build. Server preflight verified unchanged V45 migrations and protocol 3; changed backend classes restricted to StripePaymentMethods and StripeCheckoutGateway plus its inner classes. Rollback release passed startup guard.

Backup: `/var/backups/qingfeng-music/20261116-wechat-gbp-a92bd0e`. Full dump includes 17 trigger definitions. Base-table restoration into a private verification schema matched 77 tables and row fingerprints, without executing triggers or starting an application on copied data. Source production fingerprints remained unchanged. This is not a full trigger-enabled disaster recovery exercise.

Current release: `/opt/qingfeng-music/releases/20261116-wechat-gbp-a92bd0e`; service UP, running JAR hash verified; backup timer active. Public recharge page returned 200; four GBP frontend assets matched accepted build bytes exactly. Configuration SHA-256 retained `3ff16112e12f36718f1536e07ca3f09e99321a163a783ed576844ca7014a719e`.

Evidence retained locally: `.cache/wechat-gbp-deploy-20261116/`, frontend `complete-acceptance-20261115T175044100146Z`, backend `maven-full-20261116T020352636043Z`, artifact `artifact-20261116T020940974911Z` under `.cache/payment-main-integration-20261112/`.

Live GBP payment, actual Stripe delivery and bank settlement remain NOT VERIFIED. Deployment and isolated test PASS do not substitute for real payment acceptance. No real orders were created/cancelled/refunded by this deployment.

Local main includes the production-compatible branch and additional unreleased V46 work. Production uses the V45-compatible release, not the entire main tree. Main and release branch were pushed to origin. Daily local running application was not restarted or verified.
