"""Offline negative-path checks. No network, MySQL, systemd or production files."""
import importlib.util
import contextlib
import copy
import io
import json
from pathlib import Path
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('guarded_release', Path(__file__).with_name('guarded_release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class RuntimeConfigurationContract(unittest.TestCase):
    def test_unsupported_interpreter_cannot_parse_arguments_or_touch_files(self):
        output=io.StringIO()
        with mock.patch.object(release.sys,'version_info',(3,6,8)), \
             mock.patch.object(release.argparse,'ArgumentParser',side_effect=AssertionError('no argument access')), \
             mock.patch('builtins.open',side_effect=AssertionError('no filesystem access')), \
             mock.patch.object(Path,'read_text',side_effect=AssertionError('no filesystem access')), \
             contextlib.redirect_stdout(output):
            self.assertEqual(2,release.main())
        result=json.loads(output.getvalue())
        self.assertEqual('PYTHON_3_9_OR_NEWER_REQUIRED',result['reason'])
        self.assertFalse(result['productionMutation'])

    def setUp(self):
        self.old = {'spring.datasource.url': 'jdbc:mysql://127.0.0.1:3306/qingfeng_music?serverTimezone=Asia/Shanghai',
                    'spring.datasource.username': 'synthetic_account', 'spring.datasource.password': 'synthetic_password',
                    'qingfeng.paypal.enabled': 'true', 'qingfeng.paypal.accepting-new-orders': 'true',
                    'qingfeng.media.storage': 'oss'}
        self.closed = {**self.old,
            'spring.datasource.url': 'jdbc:mysql://127.0.0.1:3306/qingfeng_music?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
            'qingfeng.stripe.enabled': 'true', 'qingfeng.stripe.accepting-new-orders': 'false',
            'qingfeng.stripe.environment': 'live', 'qingfeng.stripe.account-id': release.ACCOUNT,
            'qingfeng.stripe.business-country': 'GB', 'qingfeng.stripe.payment-methods.card.enabled': 'true',
            'qingfeng.stripe.payment-methods.apple-pay.enabled': 'true',
            'qingfeng.stripe.payment-methods.wechat-pay.enabled': 'false',
            'qingfeng.stripe.api-key': 'sk_' + 'live_' + 'SyntheticOfflineFixture',
            'qingfeng.stripe.webhook-secret': 'whsec_' + 'SyntheticOfflineFixture',
            'qingfeng.credits.writer-fence.enabled': 'true',
            'qingfeng.credits.writer-fence.expected-database': 'qingfeng_music',
            'qingfeng.credits.writer-fence.expected-port': '3306',
            'qingfeng.payment.legacy-orders-read-only': 'true',
            'qingfeng.paypal.accepting-new-orders': 'false',
            'spring.flyway.enabled': 'false', 'spring.flyway.target': '44', 'spring.sql.init.mode': 'never'}

    def reject(self, code, **changes):
        with self.assertRaisesRegex(release.Blocked, code):
            release.validate_configuration(self.old, {**self.closed, **changes}, False)

    def test_closed_stage_preserves_unrelated_capabilities(self):
        release.validate_configuration(self.old, self.closed, False)
        self.assertEqual(self.closed['qingfeng.paypal.enabled'], 'true')
        self.assertEqual(self.closed['qingfeng.media.storage'], 'oss')

    def test_cannot_disable_paypal_callback_service_or_media(self):
        self.reject('UNRELATED_CONFIGURATION_CHANGE', **{'qingfeng.paypal.enabled': 'false'})
        self.reject('UNRELATED_CONFIGURATION_CHANGE', **{'qingfeng.media.storage': 'local'})

    def test_does_not_allow_database_or_password_switch(self):
        self.reject('DATABASE_ENDPOINT_CHANGED', **{'spring.datasource.url': self.closed['spring.datasource.url'].replace('qingfeng_music', 'another_schema')})
        self.reject('UNRELATED_CONFIGURATION_CHANGE', **{'spring.datasource.password': 'changed'})

    def test_utc_label_without_actual_session_change_is_rejected(self):
        self.reject('ACTUAL_UTC_SESSION_PARAMETERS_REQUIRED', **{'spring.datasource.url': self.old['spring.datasource.url'].replace('Asia/Shanghai', 'UTC')})

    def test_utc_change_cannot_silently_enable_an_unrelated_jdbc_capability(self):
        self.reject('UNRELATED_JDBC_PARAMETER_CHANGE', **{'spring.datasource.url': self.closed['spring.datasource.url'] + '&allowLoadLocalInfile=true'})

    def test_sandbox_or_placeholders_cannot_enter_live(self):
        self.reject('LITERAL_LIVE_API_CREDENTIAL_REQUIRED', **{'qingfeng.stripe.api-key': 'sk_' + 'test_' + 'SyntheticOfflineFixture'})
        self.reject('LITERAL_LIVE_API_CREDENTIAL_REQUIRED', **{'qingfeng.stripe.api-key': '${STRIPE_SECRET_KEY}'})
        self.reject('RUNTIME_MODE_OR_FINANCIAL_GUARD_MISMATCH', **{'qingfeng.stripe.environment': 'sandbox'})

    def test_wrong_account_open_wechat_or_unfenced_writer_are_rejected(self):
        for key, value in [('qingfeng.stripe.account-id', 'acct_wrong'),
                           ('qingfeng.stripe.payment-methods.wechat-pay.enabled', 'true'),
                           ('qingfeng.credits.writer-fence.enabled', 'false'),
                           ('spring.flyway.enabled', 'true'),
                           ('qingfeng.payment.legacy-orders-read-only', 'false')]:
            with self.subTest(key=key):
                self.reject('RUNTIME_MODE_OR_FINANCIAL_GUARD_MISMATCH', **{key: value})

    def test_closed_recovery_cannot_leave_canary_users_enabled(self):
        self.reject('CANARY_OWNERS_MUST_BE_EMPTY', **{'qingfeng.stripe.canary-owner-ids': '00000000-0000-0000-0000-000000000001'})

    def test_canary_requires_bounded_unique_authenticated_owner_ids(self):
        owner = '00000000-0000-0000-0000-000000000001'
        release.validate_configuration(self.old, {**self.closed, 'qingfeng.stripe.canary-owner-ids': owner}, False, True)
        for value in ('', 'not-an-owner', owner + ',' + owner,
                      ','.join('00000000-0000-0000-0000-00000000000' + str(i) for i in range(4))):
            with self.subTest(value=value), self.assertRaisesRegex(release.Blocked, 'EXPLICIT_MAX_THREE_CANARY_OWNERS_REQUIRED'):
                release.validate_configuration(self.old, {**self.closed, 'qingfeng.stripe.canary-owner-ids': value}, False, True)

    def test_public_open_does_not_retain_hidden_owner_exception(self):
        opened = {**self.closed, 'qingfeng.stripe.accepting-new-orders': 'true'}
        release.validate_configuration(self.old, opened, True)
        with self.assertRaisesRegex(release.Blocked, 'CANARY_OWNERS_MUST_BE_EMPTY'):
            release.validate_configuration(self.old, {**opened, 'qingfeng.stripe.canary-owner-ids': '00000000-0000-0000-0000-000000000001'}, True)

    def test_one_cent_authorization_cannot_enable_regular_catalog_or_invent_half_dollar(self):
        for amount in (1, 50, 990):
            with self.subTest(amount=amount), self.assertRaisesRegex(release.Blocked, 'ACTUAL_CANARY_QUOTE_EXCEEDS_APPROVED_FUNDS_SCOPE'):
                release.validate_canary_funding({'candidateJarSha256': 'a' * 64, 'currency': 'USD',
                    'selectedPackageAmountMinor': amount, 'approvedMaximumMinor': 1,
                    'approvedTransactionLimit': 2}, 'a' * 64)

    def test_funding_requires_exact_candidate_and_actual_quote_within_approved_limit(self):
        proof = {'candidateJarSha256': 'a' * 64, 'currency': 'USD', 'selectedPackageAmountMinor': 990,
                 'approvedMaximumMinor': 990, 'approvedTransactionLimit': 2}
        release.validate_canary_funding(proof, 'a' * 64)
        for changes in ({'candidateJarSha256': 'b' * 64}, {'currency': 'CNY'},
                        {'approvedTransactionLimit': 3}, {'approvedMaximumMinor': True}):
            with self.subTest(changes=changes), self.assertRaises(release.Blocked):
                release.validate_canary_funding({**proof, **changes}, 'a' * 64)


class FourChannelReleaseContract(unittest.TestCase):
    """Synthetic receipt contracts only, never real payment or server acceptance."""
    def setUp(self):
        RuntimeConfigurationContract.setUp(self)
        self.owner = '00000000-0000-0000-0000-000000000001'
        self.paypal = 'SYNTHETICMERCHANT123'
        paypal_config = {
            'qingfeng.paypal.environment': 'live',
            'qingfeng.paypal.live-enabled': 'true',
            'qingfeng.paypal.public-live-enabled': 'true',
            'qingfeng.paypal.return-base-url': 'https://cheerfulmusic.com/account',
            'qingfeng.paypal.merchant-id': self.paypal,
            'qingfeng.paypal.client-id': 'SyntheticOfflinePayPalClient',
            'qingfeng.paypal.client-secret': 'SyntheticOfflinePayPalSecret',
            'qingfeng.paypal.webhook-id': 'SyntheticOfflineWebhookId',
        }
        self.old.update(paypal_config)
        self.closed.update(paypal_config)
        self.closed.update({'qingfeng.stripe.payment-methods.card.enabled': 'false',
                            'qingfeng.stripe.payment-methods.apple-pay.enabled': 'false'})
        self.jar = 'a' * 64
        self.plan = {
            'version': 2, 'scope': 'payment-four-channel',
            'releaseChannels': sorted(release.CHANNELS),
            'stageChannels': {'closed': [], 'canary': ['card', 'apple_pay'], 'open': sorted(release.CHANNELS)},
            'paypalMerchantId': self.paypal, 'candidate': {'jarSha256': self.jar},
        }

    def configuration(self, channels, accepting=False, canary=False):
        result = {**self.closed, 'qingfeng.stripe.accepting-new-orders': 'true' if accepting else 'false',
                  'qingfeng.paypal.accepting-new-orders': 'true' if accepting and 'paypal' in channels else 'false'}
        for name, channel in [('card', 'card'), ('apple-pay', 'apple_pay'), ('wechat-pay', 'wechat_pay')]:
            result['qingfeng.stripe.payment-methods.' + name + '.enabled'] = 'true' if channel in channels else 'false'
        if canary:
            result['qingfeng.stripe.canary-owner-ids'] = self.owner
            if 'paypal' in channels:
                result['qingfeng.paypal.canary-owner-ids'] = self.owner
        result.update({'qingfeng.stripe.prices.cny.starter': '68.80',
                       'qingfeng.stripe.prices.cny.standard': '178.80',
                       'qingfeng.stripe.prices.cny.plus': '338.80'})
        return result

    def business(self):
        result = {'status': 'PASS', 'jarSha256': self.jar, 'accountId': release.ACCOUNT, 'environment': 'live',
                  **{key: 'PASS' for key in ('signedWebhook', 'creditCardPaid', 'applePayDevicePaid',
                                            'creditedExactlyOnce', 'ownershipAndCurrency')}}
        proofs = {}
        for index, channel in enumerate(sorted(release.CHANNELS), 1):
            proofs[channel] = {
                'status': 'PASS', 'candidateJarSha256': self.jar,
                'provider': 'PAYPAL' if channel == 'paypal' else 'STRIPE',
                'accountId': self.paypal if channel == 'paypal' else release.ACCOUNT,
                'environment': 'live', 'livemode': True,
                'providerStatus': 'COMPLETED' if channel == 'paypal' else 'succeeded',
                'paymentMethodType': 'card' if channel == 'apple_pay' else channel,
                'walletType': 'apple_pay' if channel == 'apple_pay' else '',
                'currency': 'CNY' if channel == 'wechat_pay' else 'USD',
                'amountMinor': 6880 if channel == 'wechat_pay' else 990,
                'providerPaymentId': 'native_payment_' + channel, 'orderId': 'order_' + channel,
                'ledgerEntryId': 'ledger_' + channel, 'sourceEvidenceSha256': str(index) * 64,
                **{key: 'PASS' for key in ('providerReadback', 'signedWebhook', 'creditedExactlyOnce',
                                           'ownershipAndCurrency', 'ledgerReconciled')},
            }
        result['channelAcceptance'] = proofs
        return result

    def funding(self, channels):
        return {'candidateJarSha256': self.jar, 'approvedTransactionLimit': 2,
                'channelQuotes': {channel: {'currency': 'CNY' if channel == 'wechat_pay' else 'USD',
                    'selectedPackageAmountMinor': 6880 if channel == 'wechat_pay' else 990,
                    'approvedMaximumMinor': 6880 if channel == 'wechat_pay' else 990,
                    'approvedTransactionLimit': 1, 'authorizationEvidenceSha256': 'b' * 64}
                    for channel in channels}}

    def test_explicit_four_channel_stage_contract_keeps_closed_empty(self):
        self.assertEqual(frozenset(), release.plan_channels(self.plan, 'closed'))
        self.assertEqual(release.CHANNELS, release.plan_channels(self.plan, 'open'))
        self.assertEqual(frozenset(('card', 'apple_pay')), release.plan_channels(self.plan, 'canary'))
        release.validate_configuration(self.old, self.closed, False, channels=[], paypal_merchant_id=self.paypal)
        self.assertEqual('true', self.closed['qingfeng.paypal.enabled'])

    def test_four_channel_target_cannot_silently_omit_paypal_or_any_other_channel(self):
        for missing in release.CHANNELS:
            plan = copy.deepcopy(self.plan)
            plan['releaseChannels'].remove(missing)
            with self.subTest(channel=missing), self.assertRaises(release.Blocked):
                release.plan_channels(plan, 'open')

    def test_unknown_duplicate_or_implicit_channel_contract_is_rejected(self):
        for channels in (None, 'card', ['card', 'card'], ['card', 'unknown'], ['apple_pay']):
            plan = copy.deepcopy(self.plan)
            plan['releaseChannels'] = channels
            with self.subTest(channels=channels), self.assertRaises(release.Blocked):
                release.plan_channels(plan, 'open')
        for changes in ({'stageChannels': {}}, {'scope': 'stripe-card-apple-pay'}, {'version': 3}):
            with self.subTest(changes=changes), self.assertRaises(release.Blocked):
                release.plan_channels({**self.plan, **changes}, 'open')

    def test_open_must_include_all_targets_and_closed_cannot_select_any_channel(self):
        for stage, channels in (('closed', ['card']), ('canary', []), ('canary', ['apple_pay']),
                                ('open', ['card', 'apple_pay', 'wechat_pay'])):
            plan = copy.deepcopy(self.plan)
            plan['stageChannels'][stage] = channels
            with self.subTest(stage=stage, channels=channels), self.assertRaises(release.Blocked):
                release.plan_channels(plan, stage)

    def test_open_four_channel_configuration_requires_all_methods_and_paypal_new_purchase(self):
        opened = self.configuration(release.CHANNELS, accepting=True)
        release.validate_configuration(self.old, opened, True, channels=release.CHANNELS, paypal_merchant_id=self.paypal)
        for key in ('qingfeng.stripe.payment-methods.wechat-pay.enabled', 'qingfeng.paypal.accepting-new-orders'):
            with self.subTest(key=key), self.assertRaisesRegex(release.Blocked, 'RUNTIME_MODE_OR_FINANCIAL_GUARD_MISMATCH'):
                release.validate_configuration(self.old, {**opened, key: 'false'}, True,
                                               channels=release.CHANNELS, paypal_merchant_id=self.paypal)

    def test_canary_paypal_is_closed_to_public_and_uses_same_authenticated_owner_list(self):
        canary = self.configuration(release.CHANNELS, canary=True)
        release.validate_configuration(self.old, canary, False, True, release.CHANNELS, self.paypal)
        self.assertEqual('false', canary['qingfeng.paypal.accepting-new-orders'])
        self.assertEqual('false', canary['qingfeng.stripe.accepting-new-orders'])
        for value in ('', 'not-an-owner', self.owner + ',' + self.owner, self.owner + ',', ',' + self.owner,
                      '00000000-0000-0000-0000-000000000002'):
            with self.subTest(value=value), self.assertRaisesRegex(release.Blocked, 'PAYPAL_CANARY_MUST_USE_SAME_EXPLICIT_OWNERS'):
                release.validate_configuration(self.old, {**canary, 'qingfeng.paypal.canary-owner-ids': value},
                                               False, True, release.CHANNELS, self.paypal)
        with self.assertRaisesRegex(release.Blocked, 'RUNTIME_MODE_OR_FINANCIAL_GUARD_MISMATCH'):
            release.validate_configuration(self.old, {**canary, 'qingfeng.paypal.accepting-new-orders': 'true'},
                                           False, True, release.CHANNELS, self.paypal)

    def test_canary_empty_list_items_and_mixed_public_mode_cannot_be_accepted(self):
        canary = self.configuration(['card', 'apple_pay'], canary=True)
        for value in (self.owner + ',', ',' + self.owner, self.owner + ', ,' + self.owner):
            with self.subTest(value=value), self.assertRaisesRegex(release.Blocked, 'EXPLICIT_MAX_THREE_CANARY_OWNERS_REQUIRED'):
                release.validate_configuration(self.old, {**canary, 'qingfeng.stripe.canary-owner-ids': value},
                                               False, True, ['card', 'apple_pay'], self.paypal)
        with self.assertRaisesRegex(release.Blocked, 'PUBLIC_AND_CANARY_STAGES_ARE_EXCLUSIVE'):
            release.validate_configuration(self.old, canary, True, True, ['card', 'apple_pay'], self.paypal)

    def test_unselected_paypal_closed_recovery_and_open_cannot_retain_canary_exception(self):
        for channels, accepting, canary in (([], False, False), (release.CHANNELS, True, False),
                                            (['card', 'apple_pay'], False, True)):
            config = self.configuration(channels, accepting, canary)
            config['qingfeng.paypal.canary-owner-ids'] = self.owner
            with self.subTest(channels=channels), self.assertRaisesRegex(release.Blocked, 'PAYPAL_CANARY_OWNERS_MUST_BE_EMPTY'):
                release.validate_configuration(self.old, config, accepting, canary, channels, self.paypal)

    def test_paypal_live_environment_merchant_and_callback_guards_cannot_be_bypassed(self):
        opened = self.configuration(release.CHANNELS, accepting=True)
        for merchant in (None, '', 'anothermerchant123'):
            with self.subTest(merchant=merchant), self.assertRaises(release.Blocked):
                release.validate_configuration(self.old, opened, True, channels=release.CHANNELS,
                                               paypal_merchant_id=merchant)
        for key, value in (('qingfeng.paypal.environment', 'sandbox'),
                           ('qingfeng.paypal.public-live-enabled', 'false'),
                           ('qingfeng.paypal.webhook-id', '${PAYPAL_WEBHOOK_ID}')):
            # Use the same value in the original: even an unchanged unsafe
            # configuration cannot satisfy the explicit new-purchase contract.
            with self.subTest(key=key), self.assertRaises(release.Blocked):
                release.validate_configuration({**self.old, key: value}, {**opened, key: value}, True,
                                               channels=release.CHANNELS, paypal_merchant_id=self.paypal)

    def test_wechat_prices_are_explicit_and_usd_catalog_is_not_mutable(self):
        opened = self.configuration(release.CHANNELS, accepting=True)
        for key, value in (('qingfeng.stripe.prices.cny.starter', '68.00'),
                           ('qingfeng.stripe.prices.cny.standard', ''),
                           ('qingfeng.stripe.prices.cny.plus', '33880')):
            with self.subTest(key=key), self.assertRaisesRegex(release.Blocked, 'APPROVED_WECHAT_CNY_QUOTES_REQUIRED'):
                release.validate_configuration(self.old, {**opened, key: value}, True,
                                               channels=release.CHANNELS, paypal_merchant_id=self.paypal)
        with self.assertRaisesRegex(release.Blocked, 'UNRELATED_CONFIGURATION_CHANGE'):
            release.validate_configuration(self.old, {**opened, 'qingfeng.payments.prices.usd.starter': '0.01'}, True,
                                           channels=release.CHANNELS, paypal_merchant_id=self.paypal)

    def test_complete_synthetic_receipt_contract_is_accepted_but_does_not_execute_anything(self):
        release.validate_business_acceptance(self.plan, self.business())

    def test_original_stripe_acceptance_fields_cannot_be_replaced_by_channel_flags(self):
        for key in ('signedWebhook', 'creditCardPaid', 'applePayDevicePaid', 'creditedExactlyOnce', 'ownershipAndCurrency'):
            business = self.business()
            business[key] = 'NOT_TESTED'
            with self.subTest(key=key), self.assertRaisesRegex(release.Blocked, 'REAL_CARD_AND_APPLE_PAY_BUSINESS_ACCEPTANCE_REQUIRED'):
                release.validate_business_acceptance(self.plan, business)

    def test_every_channel_requires_independent_real_acceptance_and_cannot_be_silently_skipped(self):
        for missing in release.CHANNELS:
            business = self.business()
            del business['channelAcceptance'][missing]
            with self.subTest(channel=missing), self.assertRaisesRegex(release.Blocked, 'EVERY_RELEASE_CHANNEL_REQUIRES_REAL_ACCEPTANCE'):
                release.validate_business_acceptance(self.plan, business)

    def test_created_unpaid_or_sandbox_objects_cannot_authorize_live_open(self):
        for channel in release.CHANNELS:
            for changes in ({'providerStatus': 'unpaid'}, {'providerStatus': 'open'}, {'status': 'SESSION_CREATED'},
                            {'environment': 'sandbox'}, {'livemode': False}, {'candidateJarSha256': 'b' * 64},
                            {'accountId': 'anotheraccount'}, {'amountMinor': True}, {'sourceEvidenceSha256': ''},
                            {'providerReadback': 'NOT_TESTED'}, {'signedWebhook': 'NOT_TESTED'},
                            {'creditedExactlyOnce': 'NOT_TESTED'}, {'ledgerReconciled': 'NOT_TESTED'}):
                business = self.business()
                business['channelAcceptance'][channel].update(changes)
                with self.subTest(channel=channel, changes=changes), self.assertRaisesRegex(release.Blocked, 'REAL_CHANNEL_PAYMENT_ACCEPTANCE_REQUIRED'):
                    release.validate_business_acceptance(self.plan, business)

    def test_card_receipt_cannot_masquerade_as_apple_pay_or_wechat_and_wechat_requires_cny(self):
        for channel, changes in (('apple_pay', {'walletType': ''}), ('apple_pay', {'paymentMethodType': 'apple_pay'}),
                                 ('wechat_pay', {'paymentMethodType': 'card'}), ('wechat_pay', {'currency': 'USD'}),
                                 ('wechat_pay', {'amountMinor': 990}), ('card', {'walletType': 'apple_pay'})):
            business = self.business()
            business['channelAcceptance'][channel].update(changes)
            with self.subTest(channel=channel, changes=changes), self.assertRaisesRegex(release.Blocked, 'REAL_CHANNEL_PAYMENT_ACCEPTANCE_REQUIRED'):
                release.validate_business_acceptance(self.plan, business)

    def test_payment_order_ledger_or_raw_evidence_cannot_accept_multiple_channels(self):
        for field in ('providerPaymentId', 'orderId', 'ledgerEntryId', 'sourceEvidenceSha256'):
            business = self.business()
            business['channelAcceptance']['apple_pay'][field] = business['channelAcceptance']['card'][field]
            with self.subTest(field=field), self.assertRaisesRegex(release.Blocked, 'SAME_PAYMENT_CANNOT_ACCEPT_MULTIPLE_CHANNELS'):
                release.validate_business_acceptance(self.plan, business)

    def test_missing_or_malformed_native_payment_order_ledger_reference_is_rejected(self):
        for field in ('providerPaymentId', 'orderId', 'ledgerEntryId'):
            business = self.business()
            business['channelAcceptance']['paypal'][field] = ''
            with self.subTest(field=field), self.assertRaisesRegex(release.Blocked, 'BOUND_CHANNEL_PAYMENT_ORDER_LEDGER_REFERENCES_REQUIRED'):
                release.validate_business_acceptance(self.plan, business)

    def test_channel_canary_funding_cannot_reuse_usd_approval_for_wechat_or_skip_paypal(self):
        selected = ['card', 'wechat_pay']
        funding = self.funding(selected)
        release.validate_canary_funding(funding, self.jar, selected)
        for changes in ({'currency': 'USD'}, {'selectedPackageAmountMinor': 1}, {'approvedMaximumMinor': 1},
                        {'authorizationEvidenceSha256': ''}, {'approvedTransactionLimit': True}):
            candidate = copy.deepcopy(funding)
            candidate['channelQuotes']['wechat_pay'].update(changes)
            with self.subTest(changes=changes), self.assertRaises(release.Blocked):
                release.validate_canary_funding(candidate, self.jar, selected)
        with self.assertRaisesRegex(release.Blocked, 'EXACT_CHANNEL_CANARY_FUNDING_REQUIRED'):
            release.validate_canary_funding(self.funding(['card']), self.jar, ['card', 'paypal'])

    def test_four_channel_canary_cannot_infer_or_expand_the_receipted_transaction_budget(self):
        with self.assertRaisesRegex(release.Blocked, 'CHANNEL_FUNDING_TRANSACTION_SCOPE_MISMATCH'):
            release.validate_canary_funding(self.funding(release.CHANNELS), self.jar, release.CHANNELS)
        funding = self.funding(['card', 'apple_pay'])
        funding['approvedTransactionLimit'] = 4
        with self.assertRaisesRegex(release.Blocked, 'CHANNEL_FUNDING_TRANSACTION_SCOPE_MISMATCH'):
            release.validate_canary_funding(funding, self.jar, ['card', 'apple_pay'])
        # These are synthetic offline contract inputs, not an authorization for
        # this release. Actual user authorization remains USD0.01 x 2.
        explicit = self.funding(release.CHANNELS)
        explicit['approvedTransactionLimit'] = 4
        release.validate_canary_funding(explicit, self.jar, release.CHANNELS)
        for value in (0, 9, True):
            with self.subTest(limit=value), self.assertRaisesRegex(release.Blocked, 'EXACT_CHANNEL_CANARY_FUNDING_REQUIRED'):
                release.validate_canary_funding({**explicit, 'approvedTransactionLimit': value}, self.jar, release.CHANNELS)

    def test_missing_paypal_proof_stops_open_before_reading_config_or_mutating_server(self):
        business = self.business()
        del business['channelAcceptance']['paypal']
        with mock.patch.object(release, 'check_release'), mock.patch.object(release, 'check_install_receipts'), \
             mock.patch.object(release, 'current_matches'), mock.patch.object(release, 'receipt', return_value=business), \
             mock.patch.object(release, 'secure', side_effect=AssertionError('must not read configuration')), \
             mock.patch.object(release, 'stop_units') as stop, mock.patch.object(release, 'atomic_copy') as write, \
             mock.patch.object(release, 'startup') as start:
            with self.assertRaisesRegex(release.Blocked, 'EVERY_RELEASE_CHANNEL_REQUIRES_REAL_ACCEPTANCE'):
                release.install(self.plan, Path('unused-offline-backup'), 'open')
        stop.assert_not_called()
        write.assert_not_called()
        start.assert_not_called()


if __name__ == '__main__':
    unittest.main()
