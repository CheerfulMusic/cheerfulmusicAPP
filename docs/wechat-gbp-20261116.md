# WeChat Pay GBP support — 2026-11-16

Implemented on local main. Not deployed; no production configuration, database or provider transactions changed.

Approved GBP prices: 100 credits £7.99; 300 credits £19.99; 700 credits £39.99. Existing USD and CNY quotes remain unchanged. GBP is a separate immutable offer ID (`starter/standard/plus-GBP-wechat_pay`) routed to WeChat Pay, not card. Existing order reconciliation continues using the original currency, amount and credit snapshot.

UK merchant accounts expose CNY and GBP. US accounts expose CNY and USD. The gateway verifies the actual Stripe merchant country before purchase; a configured country cannot bypass it. Frontend accepts GBP metadata, displays £ and sends only the server offer ID and payment method.

Optional overrides: `qingfeng.stripe.prices.gbp.starter`, `.standard`, `.plus`; configure all three together. Missing all uses the approved defaults above. Partial/invalid/nonpositive/sub-penny values fail startup configuration validation.

Evidence: backend StripePaymentMethodsTest and StripeWalletGatewayTest: 27 passed, zero failures/errors/skips. Frontend: 787 passed; vue-tsc typecheck passed. Tests use fake HTTP fixtures, no real payment calls or database connections. Logs are retained locally under `.cache/gbp-*-test.log` and `.cache/gbp-typecheck.log`.

GBP live payment and GBP refund/settlement end-to-end remain NOT VERIFIED. This does not establish USD bank payout availability: order currency and bank settlement currency are separate.

Before production deployment run the project release process against the then-current production/main baseline, including required build checks, backup and rollback verification. No migration is needed for this implementation. Do not deploy the complete current main without reconciling its other changes with production.

Stripe source checked: https://docs.stripe.com/payments/wechat-pay#supported-currencies — GBP maps to United Kingdom, CNY to all supported countries, USD to United States.
