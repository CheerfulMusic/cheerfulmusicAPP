"""Offline negative-path checks. No network, MySQL, systemd or production files."""
import importlib.util
import contextlib
import copy
import io
import json
import tempfile
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
            'spring.flyway.enabled': 'false', 'spring.flyway.target': '45', 'spring.sql.init.mode': 'never'}

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


class Financial45OperatorContract(unittest.TestCase):
    """Operator ordering and refusal tests. Mock execution is never DB evidence."""
    def setUp(self):
        FourChannelReleaseContract.setUp(self)
        self.plan['candidate']['directory'] = str(release.APP / 'releases' / 'synthetic-v45')
        self.plan['database'] = {'uuid': release.UUID, 'schema': release.DATABASE, 'port': 3306}
        self.plan['schemaVersions'] = {'core': 44, 'financial': 45, 'protocol': 3}
        self.plan['operator'] = {name: {'path': '/private/' + name, 'sha256': 'b' * 64}
            for name in ('java', 'databaseProperties', 'migrationProperties', 'carryForwardProperties')}
        self.source, self.plan_sha = 'c' * 64, 'd' * 64
        self.values = {'expected.uuid': release.UUID, 'expected.database': release.DATABASE, 'expected.port': '3306',
            'candidate.jar.path': str(Path(self.plan['candidate']['directory']) / 'app.jar'),
            'candidate.jar.sha256': self.jar, 'source.sha256': self.source, 'plan.sha256': self.plan_sha,
            'migration.version': '45', 'migration.sha256': release.FINANCIAL_SQL_SHA256,
            'core.manifest.sha256': release.CORE_MANIFEST_SHA256, 'migration.authorized': 'true'}
        self.db = {'spring.datasource.url': 'jdbc:mysql://127.0.0.1:3306/qingfeng_music?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
                   'spring.datasource.username': 'synthetic', 'spring.datasource.password': 'synthetic-not-real'}
        self.inputs = (Path('/usr/lib/jvm/synthetic-java17/bin/java'), Path('/private/db.properties'),
                       Path('/private/approved.properties'), self.values, self.db)
        self.native = {'status': 'PASS', 'candidateJarSha256': self.jar, 'databaseUuid': release.UUID,
            'database': release.DATABASE, 'port': 3306, 'sourceSha256': self.source,
            'planSha256': self.plan_sha, 'schemaVersion': 45, 'coreVersion': 44, 'migrationsExecuted': 1,
            'financialMigrationSha256': release.FINANCIAL_SQL_SHA256, 'coreManifestSha256': release.CORE_MANIFEST_SHA256,
            'state': 'PREPARING', 'protocol': 3}

    def test_core44_is_not_the_financial_runtime_target_anymore(self):
        RuntimeConfigurationContract.setUp(self)
        release.validate_configuration(self.old, self.closed, False)
        with self.assertRaisesRegex(release.Blocked, 'RUNTIME_MODE_OR_FINANCIAL_GUARD_MISMATCH'):
            release.validate_configuration(self.old, {**self.closed, 'spring.flyway.target': '44'}, False)

    def test_operator_input_requires_exact_database_artifact_and_canonical_45(self):
        paths = {'java': self.inputs[0], 'databaseProperties': self.inputs[1],
                 'migrationProperties': self.inputs[2], 'carryForwardProperties': self.inputs[2]}
        def maps(path): return self.db if path == self.inputs[1] else self.values
        with mock.patch.object(release, 'operator_file', side_effect=lambda plan, name: paths[name]), \
             mock.patch.object(release, 'property_map', side_effect=maps):
            release.operator_inputs(self.plan, 'migration')
            for key, value in (('migration.version', '44'), ('migration.sha256', 'e' * 64),
                               ('core.manifest.sha256', 'e' * 64), ('expected.database', 'other'),
                               ('candidate.jar.sha256', 'e' * 64)):
                original = self.values[key]
                self.values[key] = value
                with self.subTest(key=key), self.assertRaises(release.Blocked):
                    release.operator_inputs(self.plan, 'migration')
                self.values[key] = original

    def test_operator_connection_cannot_target_remote_or_non_utc_or_unresolved_credentials(self):
        paths = {'java': self.inputs[0], 'databaseProperties': self.inputs[1], 'migrationProperties': self.inputs[2]}
        for changes in ({'spring.datasource.url': self.db['spring.datasource.url'].replace('127.0.0.1', 'cheerfulmusic.com')},
                        {'spring.datasource.url': self.db['spring.datasource.url'].replace('forceConnectionTimeZoneToSession=true', 'forceConnectionTimeZoneToSession=false')},
                        {'spring.datasource.password': '${PASSWORD}'}):
            changed = {**self.db, **changes}
            with self.subTest(changes=list(changes)), mock.patch.object(release, 'operator_file', side_effect=lambda plan, name: paths[name]), \
                 mock.patch.object(release, 'property_map', side_effect=lambda p: changed if p == self.inputs[1] else self.values), \
                 self.assertRaises(release.Blocked):
                release.operator_inputs(self.plan, 'migration')

    def test_java_operator_launch_uses_pinned_fat_jar_and_sanitized_environment(self):
        native = {**self.native, 'unapprovedOutput': 'must-not-enter-evidence'}
        with mock.patch.object(release, 'operator_inputs', return_value=self.inputs), \
             mock.patch.object(release, 'command', return_value=b'private Flyway/driver output suppressed\n' + json.dumps(native).encode()) as run:
            result = release.run_operator(self.plan, 'migration', 'migrate')
        args = run.call_args.args[0]
        self.assertIn('-Dloader.main=' + release.MIGRATION_CLASS, args)
        self.assertIn('-Dloader.config.location=file:/dev/null', args)
        self.assertIn('org.springframework.boot.loader.launch.PropertiesLauncher', args)
        self.assertNotIn('-jar', args)
        self.assertEqual({'PATH', 'LANG', 'HOME'}, set(run.call_args.kwargs['env']))
        self.assertNotIn('unapprovedOutput', result)
        self.assertEqual(self.source, result['sourceSha256'])

    def test_operator_native_database_source_and_artifact_mismatches_are_rejected(self):
        for changes in ({'status': 'BLOCKED'}, {'databaseUuid': 'wrong'}, {'database': 'other'},
                        {'candidateJarSha256': 'e' * 64}, {'sourceSha256': ''}):
            with self.subTest(changes=changes), mock.patch.object(release, 'operator_inputs', return_value=self.inputs), \
                 mock.patch.object(release, 'command', return_value=json.dumps({**self.native, **changes}).encode()), \
                 self.assertRaises(release.Blocked):
                release.run_operator(self.plan, 'migration', 'migrate')

    def test_schema_validate_is_read_only_and_cannot_be_reported_as_migration(self):
        native = {**self.native, 'schemaVersion': 44, 'migrationsExecuted': 0}
        with contextlib.ExitStack() as stack:
            for name in ('check_release', 'check_build_receipt', 'current_matches'):
                stack.enter_context(mock.patch.object(release, name))
            stack.enter_context(mock.patch.object(release, 'operator_inputs', return_value=self.inputs))
            runner = stack.enter_context(mock.patch.object(release, 'run_operator', return_value=native))
            backup = stack.enter_context(mock.patch.object(release, 'check_operator_backup'))
            writer = stack.enter_context(mock.patch.object(release, 'fresh_operator_contract'))
            result = release.operator_stage(self.plan, Path('unused'), 'schema-validate')
        self.assertEqual(44, result['schemaVersion'])
        self.assertEqual('validate', runner.call_args.args[2])
        backup.assert_not_called()
        writer.assert_not_called()

    def test_no_ddl_before_stopped_writers_actual_restore_and_precise_authorization(self):
        for failure_point in ('stopped', 'check_operator_backup', 'approved_operator_scope'):
            with self.subTest(failure_point=failure_point), contextlib.ExitStack() as stack:
                for name in ('check_release', 'check_build_receipt', 'current_matches', 'stopped', 'verify_media_paused'):
                    stack.enter_context(mock.patch.object(release, name))
                stack.enter_context(mock.patch.object(release, 'operator_inputs', return_value=self.inputs))
                stack.enter_context(mock.patch.object(release, 'check_operator_backup', return_value={'tableFingerprint': {}}))
                stack.enter_context(mock.patch.object(release, 'approved_operator_scope'))
                stack.enter_context(mock.patch.object(release, failure_point, side_effect=release.Blocked('SYNTHETIC_PRECONDITION_FAILURE')))
                runner = stack.enter_context(mock.patch.object(release, 'run_operator'))
                with self.assertRaisesRegex(release.Blocked, 'SYNTHETIC_PRECONDITION_FAILURE'):
                    release.operator_stage(self.plan, Path('unused'), 'migrate-45')
                runner.assert_not_called()

    def test_schema_authorization_is_approval_not_a_fake_test_pass_and_is_scope_bound(self):
        approved = {'status': 'APPROVED', 'operation': 'MIGRATE_CANONICAL_44_TO_FINANCIAL_45',
                    'database': self.plan.get('database'), 'candidateJarSha256': self.jar,
                    'sourceSha256': self.source, 'userAuthorizationEvidenceSha256': 'e' * 64,
                    'coreVersion': 44, 'financialVersion': 45, 'financialMigrationSha256': release.FINANCIAL_SQL_SHA256}
        self.plan['receipts'] = {'schemaAuthorization': {'path': '/synthetic', 'sha256': 'f' * 64}}
        for changes in ({'status': 'PASS'}, {'sourceSha256': 'e' * 64}, {'financialVersion': 44},
                        {'userAuthorizationEvidenceSha256': ''}):
            with self.subTest(changes=changes), mock.patch.object(release, 'pinned_json', return_value={**approved, **changes}), \
                 self.assertRaises(release.Blocked):
                release.approved_operator_scope(self.plan, 'schemaAuthorization', self.values, 'MIGRATE_CANONICAL_44_TO_FINANCIAL_45')

    def test_migration_stage_writes_only_native_pass_receipt_and_does_not_activate_or_start(self):
        with contextlib.ExitStack() as stack:
            for name in ('check_release', 'check_build_receipt', 'current_matches', 'stopped', 'approved_operator_scope', 'root_observation', 'verify_media_paused'):
                stack.enter_context(mock.patch.object(release, name))
            stack.enter_context(mock.patch.object(release, 'operator_inputs', return_value=self.inputs))
            stack.enter_context(mock.patch.object(release, 'check_operator_backup', return_value={'tableFingerprint': {'native': 'same'}}))
            stack.enter_context(mock.patch.object(release, 'fingerprint', return_value={'native': 'same'}))
            stack.enter_context(mock.patch.object(release, 'fresh_operator_contract', return_value=Path('/private/effective.properties')))
            runner = stack.enter_context(mock.patch.object(release, 'run_operator', return_value=self.native))
            output = stack.enter_context(mock.patch.object(release, 'exclusive_json'))
            start = stack.enter_context(mock.patch.object(release, 'startup'))
            result = release.operator_stage(self.plan, Path('unused'), 'migrate-45')
        self.assertEqual(('migration', 'migrate'), runner.call_args.args[1:3])
        self.assertEqual('PREPARING', result['state'])
        self.assertEqual('migration-45-receipt.json', output.call_args.args[0].name)
        start.assert_not_called()

    def test_java_failure_after_ddl_never_starts_old_or_new_writer_or_fakes_receipt(self):
        with contextlib.ExitStack() as stack:
            for name in ('check_release', 'check_build_receipt', 'current_matches', 'stopped', 'approved_operator_scope', 'verify_media_paused'):
                stack.enter_context(mock.patch.object(release, name))
            stack.enter_context(mock.patch.object(release, 'operator_inputs', return_value=self.inputs))
            stack.enter_context(mock.patch.object(release, 'check_operator_backup', return_value={'tableFingerprint': {}}))
            stack.enter_context(mock.patch.object(release, 'fingerprint', return_value={}))
            stack.enter_context(mock.patch.object(release, 'fresh_operator_contract', return_value=Path('/private/effective.properties')))
            stack.enter_context(mock.patch.object(release, 'run_operator', side_effect=release.Blocked('SYNTHETIC_DDL_TIMEOUT')))
            start = stack.enter_context(mock.patch.object(release, 'startup'))
            output = stack.enter_context(mock.patch.object(release, 'exclusive_json'))
            with self.assertRaisesRegex(release.Blocked, 'SYNTHETIC_DDL_TIMEOUT'):
                release.operator_stage(self.plan, Path('unused'), 'migrate-45')
        start.assert_not_called()
        output.assert_not_called()

    def test_fresh_writer_proof_changes_only_observation_not_external_approval_or_other_proof_pins(self):
        cache = Path(__file__).resolve().parents[2] / '.cache'
        cache.mkdir(exist_ok=True)
        values = {**self.values}
        values['candidate.jar.path'] = Path(values['candidate.jar.path']).as_posix()
        for kind in ('backup-restore', 'rollback', 'legacy-boundary'):
            values['receipt.' + kind + '.path'] = '/private/' + kind + '.json'
            values['receipt.' + kind + '.sha256'] = 'e' * 64
        with tempfile.TemporaryDirectory(prefix='writer-contract-', dir=cache) as temporary:
            with mock.patch.object(release, 'stopped'), \
                 mock.patch.object(release, 'pinned_json', return_value={'status': 'PASS'}) as proof, \
                 mock.patch.object(release, 'property_map', return_value=self.db), \
                 mock.patch.object(release, 'root_observation', return_value={'otherConnections': 0, 'otherApplicationAccountConnections': 0}):
                effective = release.fresh_operator_contract(self.plan, Path(temporary), 'carry', 'carry-apply', values, self.db)
            actual = release.property_map(effective)
            self.assertEqual(self.source, actual['source.sha256'])
            self.assertEqual(self.plan_sha, actual['plan.sha256'])
            self.assertEqual(self.jar, actual['candidate.jar.sha256'])
            for kind in ('backup-restore', 'rollback', 'legacy-boundary'):
                self.assertEqual(values['receipt.' + kind + '.sha256'], actual['receipt.' + kind + '.sha256'])
            self.assertEqual(3, proof.call_count)
            observed = json.loads(Path(actual['receipt.writers.path']).read_text())
            self.assertEqual(0, observed['otherApplicationAccountConnections'])
            self.assertTrue(observed['globalProcesslistVerified'])
            self.assertEqual(self.source, observed['sourceSha256'])
            self.assertEqual(release.sha(actual['receipt.writers.path']), actual['receipt.writers.sha256'])

    def test_root_observer_rejects_remaining_application_connections_even_on_other_schema(self):
        base = {'uuid': release.UUID, 'schema': release.DATABASE, 'port': 3306, 'otherConnections': 0}
        with mock.patch.object(release, 'mysql', return_value=b'GRANT ALL PRIVILEGES ON *.* TO root'), \
             mock.patch.object(release, 'json_rows', side_effect=[[base], [{'connections': 1}]]), \
             self.assertRaisesRegex(release.Blocked, 'OTHER_APPLICATION_ACCOUNT_CONNECTIONS_REMAIN'):
            release.root_observation(self.plan, 'synthetic')

    def test_reviewed_existing_local_root_is_operator_only_and_requires_literal_empty_password_key(self):
        paths = {'java': self.inputs[0], 'databaseProperties': self.inputs[1], 'migrationProperties': self.inputs[2]}
        values = {**self.values, 'allow-local-root-with-empty-password': 'true'}
        db = {**self.db, 'spring.datasource.username': 'root', 'spring.datasource.password': ''}
        self.plan['operatorIdentity'] = {'mode': 'existing-local-root-empty-password', 'username': 'root', 'runtimeAccountUnchanged': True}
        with mock.patch.object(release, 'operator_file', side_effect=lambda plan, name: paths[name]), \
             mock.patch.object(release, 'property_map', side_effect=lambda p: db if p == self.inputs[1] else values):
            release.operator_inputs(self.plan, 'migration')
            for changed_db, changed_values, changed_plan in (
                (db, self.values, self.plan),
                ({k: v for k, v in db.items() if k != 'spring.datasource.password'}, values, self.plan),
                ({**db, 'spring.datasource.username': 'another_admin'}, values, self.plan),
                (db, values, {**self.plan, 'operatorIdentity': {}})):
                with self.subTest(dbkeys=list(changed_db), flag=changed_values.get('allow-local-root-with-empty-password')), \
                     mock.patch.object(release, 'property_map', side_effect=lambda p: changed_db if p == self.inputs[1] else changed_values), \
                     self.assertRaises(release.Blocked):
                    release.operator_inputs(changed_plan, 'migration')

    def test_operator_cannot_override_identity_through_query_credentials_even_if_blank(self):
        paths = {'java': self.inputs[0], 'databaseProperties': self.inputs[1], 'migrationProperties': self.inputs[2]}
        for suffix in ('&user=root', '&password=', '&PassWord=hidden', '&password1=hidden'):
            db = {**self.db, 'spring.datasource.url': self.db['spring.datasource.url'] + suffix}
            with self.subTest(parameter=suffix.split('=')[0]), \
                 mock.patch.object(release, 'operator_file', side_effect=lambda plan, name: paths[name]), \
                 mock.patch.object(release, 'property_map', side_effect=lambda p: db if p == self.inputs[1] else self.values), \
                 self.assertRaisesRegex(release.Blocked, 'OPERATOR_URL_CREDENTIAL_OVERRIDE_FORBIDDEN'):
                release.operator_inputs(self.plan, 'migration')

    def test_admin_cutover_still_observes_original_runtime_account_and_does_not_replace_it(self):
        cache = Path(__file__).resolve().parents[2] / '.cache'
        cache.mkdir(exist_ok=True)
        values = {**self.values, 'allow-local-root-with-empty-password': 'true'}
        values['candidate.jar.path'] = Path(values['candidate.jar.path']).as_posix()
        for kind in ('backup-restore', 'rollback', 'legacy-boundary'):
            values['receipt.' + kind + '.path'] = '/private/' + kind + '.json'
            values['receipt.' + kind + '.sha256'] = 'e' * 64
        db = {**self.db, 'spring.datasource.username': 'root', 'spring.datasource.password': ''}
        self.plan['operatorIdentity'] = {'mode': 'existing-local-root-empty-password', 'username': 'root', 'runtimeAccountUnchanged': True}
        with tempfile.TemporaryDirectory(prefix='admin-writer-contract-', dir=cache) as temporary, \
             mock.patch.object(release, 'stopped'), mock.patch.object(release, 'pinned_json', return_value={'status': 'PASS'}), \
             mock.patch.object(release, 'property_map', return_value=self.db), \
             mock.patch.object(release, 'root_observation', return_value={'otherConnections': 0, 'otherApplicationAccountConnections': 0}) as observe:
            release.fresh_operator_contract(self.plan, Path(temporary), 'migration', 'migrate-45', values, db)
        self.assertEqual(['synthetic', 'root'], [call.args[1] for call in observe.call_args_list])
        self.assertEqual('synthetic', self.db['spring.datasource.username'])
        self.assertEqual('synthetic-not-real', self.db['spring.datasource.password'])


class MediaCronReleaseContract(unittest.TestCase):
    def test_pause_changes_one_exact_job_and_preserves_other_jobs_and_environment(self):
        original = ('MAILTO=ops@example.invalid\n# personal note\n'
                    '5 2 * * * /usr/local/bin/unrelated-backup\n' + release.MEDIA_ENTRY + '\n').encode()
        paused = release.paused_crontab(original)
        self.assertIn((release.MEDIA_PAUSE_MARKER + release.MEDIA_ENTRY + '\n').encode(), paused)
        self.assertEqual(['MAILTO=ops@example.invalid', '5 2 * * * /usr/local/bin/unrelated-backup'], release.cron_active_lines(paused))
        self.assertIn(b'# personal note\n', paused)
        for bad in (b'# no project job\n', original + release.MEDIA_ENTRY.encode() + b'\n'):
            with self.subTest(bad=bad), self.assertRaisesRegex(release.Blocked, 'EXACTLY_ONE_PROJECT_MEDIA_CRON_REQUIRED'):
                release.paused_crontab(bad)

    def test_intervening_crontab_edit_blocks_before_install_or_temporary_file_creation(self):
        with mock.patch.object(release, 'secure', return_value=Path('unused')), \
             mock.patch.object(release, 'sha', return_value='b' * 64), \
             mock.patch.object(release.os, 'open') as create, mock.patch.object(release, 'command') as install:
            with self.assertRaisesRegex(release.Blocked, 'ROOT_CRONTAB_CHANGED_DO_NOT_OVERWRITE'):
                release.install_crontab_checked('a' * 64, b'not-a-real-crontab', Path('unused'))
        create.assert_not_called()
        install.assert_not_called()

    def test_pause_receipt_is_private_and_resume_cannot_overwrite_new_jobs_or_restore_unsafe_monitor(self):
        cache = Path(__file__).resolve().parents[2] / '.cache'
        cache.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='cron-contract-', dir=cache) as temporary:
            root = Path(temporary)
            spool, evidence = root / 'root-crontab', root / 'private-backup'
            original = ('# existing comment\n' + release.MEDIA_ENTRY + '\n'
                        '5 2 * * * /usr/local/bin/unrelated-backup\n').encode()
            spool.write_bytes(original)
            control = {'entry': release.MEDIA_ENTRY, 'scriptSha256': 'd' * 64,
                       'expectedCrontabSha256': release.sha(spool)}
            plan = {'releaseId': 'synthetic-only', 'candidate': {'jarSha256': 'a' * 64}, 'mediaCron': control}
            def native_install(args, **kwargs):
                self.assertEqual(['crontab', '-u', 'root'], args[:3])
                spool.write_bytes(Path(args[3]).read_bytes())
                return b''
            with mock.patch.object(release, 'ROOT_CRONTAB', spool), \
                 mock.patch.object(release, 'secure', side_effect=lambda p, **kwargs: Path(p)), \
                 mock.patch.object(release, 'current_matches'), \
                 mock.patch.object(release, 'validate_media_cron', return_value=control), \
                 mock.patch.object(release, 'media_cron_directory', return_value=evidence), \
                 mock.patch.object(release, 'media_process_ids', return_value=[]), \
                 mock.patch.object(release, 'command', side_effect=native_install) as install:
                result = release.media_cron_stage(plan, 'cron-pause')
                self.assertEqual('PASS', result['status'])
                self.assertFalse(result['wholeCronServiceStopped'])
                self.assertEqual(original, (evidence / 'root-crontab-before.txt').read_bytes())
                paused = spool.read_bytes()
                self.assertEqual(1, install.call_count)
                spool.write_bytes(paused + b'7 3 * * * /new-concurrent-job\n')
                with self.assertRaisesRegex(release.Blocked, 'PROJECT_MEDIA_CRON_NOT_PAUSED_OR_PROCESS_STILL_RUNNING'):
                    release.media_cron_stage(plan, 'cron-resume')
                self.assertEqual(1, install.call_count)
                self.assertIn(b'/new-concurrent-job', spool.read_bytes())
                # Even without a concurrent edit, the old script's missing
                # release lock must block resume; do not invent a PASS proof.
                spool.write_bytes(paused)
                proof = {'candidateJarSha256': 'a' * 64, 'mediaScriptSha256': 'd' * 64,
                         'releaseLockCooperates': False, 'configurationWritesAtomic': True,
                         'applicationHealthVerified': True, 'ledgerWriterCompatible': True}
                with mock.patch.object(release, 'receipt', return_value=proof), \
                     mock.patch.object(release, 'sha', side_effect=lambda p: 'd' * 64 if p == release.MEDIA_SCRIPT else __import__('hashlib').sha256(Path(p).read_bytes()).hexdigest()), \
                     self.assertRaisesRegex(release.Blocked, 'MEDIA_SELF_HEAL_COMPATIBILITY_REQUIRED'):
                    release.media_cron_stage(plan, 'cron-resume')
                self.assertEqual(1, install.call_count)

    def test_pause_does_not_claim_process_drained_when_script_remains_running(self):
        cache = Path(__file__).resolve().parents[2] / '.cache'
        cache.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='cron-drain-', dir=cache) as temporary:
            root = Path(temporary)
            spool, evidence = root / 'root-crontab', root / 'private-backup'
            spool.write_bytes((release.MEDIA_ENTRY + '\n').encode())
            control = {'expectedCrontabSha256': release.sha(spool), 'scriptSha256': 'd' * 64}
            def install_paused(*args):
                value = (release.MEDIA_PAUSE_MARKER + release.MEDIA_ENTRY + '\n').encode()
                spool.write_bytes(value)
                return value
            with mock.patch.object(release, 'ROOT_CRONTAB', spool), \
                 mock.patch.object(release, 'secure', side_effect=lambda p, **kwargs: Path(p)), \
                 mock.patch.object(release, 'current_matches'), \
                 mock.patch.object(release, 'validate_media_cron', return_value=control), \
                 mock.patch.object(release, 'media_cron_directory', return_value=evidence), \
                 mock.patch.object(release, 'install_crontab_checked', side_effect=install_paused), \
                 mock.patch.object(release, 'media_process_ids', return_value=[12345]), \
                 mock.patch.object(release.time, 'monotonic', side_effect=[0, 31]), \
                 self.assertRaisesRegex(release.Blocked, 'PROJECT_MEDIA_PROCESS_NOT_DRAINED_CRON_REMAINS_PAUSED'):
                release.media_cron_stage({'releaseId': 'synthetic-only'}, 'cron-pause')
            self.assertFalse((evidence / 'paused-receipt.json').exists())
            original = (evidence / 'root-crontab-before.txt').read_bytes()
            with mock.patch.object(release, 'ROOT_CRONTAB', spool), \
                 mock.patch.object(release, 'secure', side_effect=lambda p, **kwargs: Path(p)), \
                 mock.patch.object(release, 'current_matches'), \
                 mock.patch.object(release, 'validate_media_cron', return_value=control), \
                 mock.patch.object(release, 'media_cron_directory', return_value=evidence), \
                 mock.patch.object(release, 'media_process_ids', return_value=[]), \
                 mock.patch.object(release, 'command') as no_reinstall:
                result = release.media_cron_stage({'releaseId': 'synthetic-only'}, 'cron-pause')
            self.assertEqual('PASS', result['status'])
            self.assertEqual(original, (evidence / 'root-crontab-before.txt').read_bytes())
            no_reinstall.assert_not_called()


class CompositeRestoreContract(unittest.TestCase):
    """Synthetic validator fixtures only. These tests never attest an actual restore."""
    def setUp(self):
        self.jar, self.commit = 'a' * 64, 'b' * 40
        self.plan = {'database': {'uuid': release.UUID, 'schema': release.DATABASE, 'port': 3306},
                     'candidate': {'sourceCommit': self.commit, 'jarSha256': self.jar}}
        self.backup = {'status': 'PASS', 'database': self.plan['database'], 'databaseSha256': 'c' * 64,
                       'tableFingerprint': {'credit_ledger': {}}}
        self.actual = {'status': 'PASS', 'scope': 'ACTUAL_SERVER_LOCAL_DATA_RESTORE_ONLY',
            'backupDatabaseSha256': 'c' * 64, 'actualRestoreExecuted': True, 'dataIntegrityVerified': True,
            'sourceUnchanged': True, 'creditJournalVerified': True, 'fullBaseTableCount': 1,
            'applicationStarted': False, 'supplierCalls': False}
        isolation = {'uuid': '05c27d3c-dda3-11f1-aba0-84a938421519', 'port': 23316,
                     'single_schema_grants': True, 'production_schema_visible': False}
        history = [{'version': str(i), 'script': 'V' + str(i) + '__fixture.sql', 'checksum': 1,
                    'success': 1, 'type': 'SQL'} for i in range(1, 46)]
        history[-1].update(script='V45__credit_entitlement_integration.sql', checksum=-2125061473)
        snapshot = {'financial_conservation': 'PASS', 'flyway': history, 'triggers': [{} for _ in range(17)]}
        self.raw = {
            'artifact': {'status': 'PASS', 'source_commit': self.commit, 'jar_sha256': self.jar, 'fresh_export': True,
                         'runtime_credentials_loaded': False, 'all_production_class_bytes_match_tested': True,
                         'all_frontend_bytes_match_acceptance': True},
            'packagedFinancial': {'status': 'PASS', 'scope': 'PACKAGED_LOCAL_SYNTHETIC_MYSQL_COMMANDS_ONLY',
                'source_commit': self.commit, 'jar_sha256': self.jar, 'isolation': isolation,
                'production_access': False, 'vendor_calls': False, 'spring_application_started': False,
                'original_money_unchanged': True, 'migrations_executed': 1, 'replay_migrations_executed': 0,
                'final_state': 'ACTIVE', 'historical_allocations_fabricated': False,
                'stages': [{'stage': name, 'status': 'BLOCKED' if name == 'reject-without-authorization' else 'PASS',
                    'exit_code': 2 if name == 'reject-without-authorization' else 0} for name in
                    ('read-only-plan', 'validate-core44', 'reject-without-authorization', 'migrate-financial45',
                     'replay-financial45', 'apply-carry-forward', 'activate-carry-forward', 'verify-carry-forward')]},
            'isolatedBackupRestore': {'status': 'PASS', 'scope': 'LOCAL_23316_ISOLATED_APPLICATION_COHORT_ONLY',
                'stage': 'COMPLETE', 'production_access': False, 'provider_calls': False,
                'original_database_writes': False, 'services_started': False, 'source_unchanged_after_restore': True,
                'source_identity': isolation, 'restore_identity': isolation, 'restore_database': 'synthetic_restore',
                'source_snapshot': snapshot, 'restore_snapshot': snapshot,
                'restore_comparison': {'semantic_ddl_columns_indexes_foreign_keys_checks_equal': True}},
            'isolatedJarStartup': {'status': 'PASS', 'scope': 'PACKAGED_RESTORED_SYNTHETIC_DB_STARTUP_ONLY',
                'source_commit': self.commit, 'jar_sha256': self.jar, 'uuid': isolation['uuid'], 'database': 'synthetic_restore',
                'production_access': False, 'vendor_calls': False, 'migrations_enabled': False, 'provider_or_money_actions': 0,
                'owned_process_stopped': True, 'restored_financial_facts_unchanged': True, 'payment_catalogs_disabled': True,
                'financial_snapshot_before': {'credit_ledger': {'rows': 3}},
                'financial_snapshot_after': {'credit_ledger': {'rows': 3}}}}
        self.refs = {name: {'path': '/synthetic/' + name + '.json', 'sha256': 'd' * 64} for name in self.raw}

    def validate(self, raw=None, frozen=None):
        return release.validate_restore_evidence(self.plan, self.backup, self.actual,
                    self.raw if raw is None else raw, self.refs, frozen)

    def test_preplan_composition_keeps_data_scopes_and_never_invents_frozen_source(self):
        value = self.validate()
        self.assertEqual('PASS', value['status'])
        self.assertFalse(value['sourceBound'])
        self.assertNotIn('sourceSha256', value)
        self.assertFalse(value['productionCloneApplicationStarted'])
        self.assertFalse(value['productionCloneMigratedTo45'])
        self.assertEqual('LOCAL_SYNTHETIC_ONLY', value['compatibilityData'])
        self.assertEqual(self.refs, value['rawEvidence'])
        self.assertEqual('NOT_TESTED', value['realPaymentAcceptance'])

    def test_missing_failed_or_other_scope_proofs_never_compose_pass(self):
        for name in self.raw:
            for field, value in (('status', 'FAIL'), ('scope', 'MOCK_CONTRACT_ONLY')):
                if field == 'scope' and name == 'artifact': continue
                raw = copy.deepcopy(self.raw); raw[name][field] = value
                with self.subTest(name=name, field=field), self.assertRaises(release.Blocked): self.validate(raw)
            raw = copy.deepcopy(self.raw); del raw[name]
            with self.subTest(missing=name), self.assertRaises(release.Blocked): self.validate(raw)

    def test_different_jars_commits_or_restore_schema_are_rejected(self):
        for name in ('artifact', 'packagedFinancial', 'isolatedJarStartup'):
            for field, value in (('source_commit', 'e' * 40), ('jar_sha256', 'e' * 64)):
                raw = copy.deepcopy(self.raw); raw[name][field] = value
                with self.subTest(name=name, field=field), self.assertRaises(release.Blocked): self.validate(raw)
        raw = copy.deepcopy(self.raw); raw['isolatedJarStartup']['database'] = 'different'
        with self.assertRaises(release.Blocked): self.validate(raw)

    def test_incomplete_command_execution_and_mutated_restored_finance_are_rejected(self):
        raw = copy.deepcopy(self.raw); raw['packagedFinancial']['stages'].pop()
        with self.assertRaises(release.Blocked): self.validate(raw)
        raw = copy.deepcopy(self.raw); raw['isolatedJarStartup']['financial_snapshot_after']['credit_ledger']['rows'] = 4
        with self.assertRaises(release.Blocked): self.validate(raw)
        raw = copy.deepcopy(self.raw); raw['isolatedBackupRestore']['restore_snapshot']['flyway'][-1]['checksum'] = 0
        with self.assertRaises(release.Blocked): self.validate(raw)

    def test_source_binding_requires_real_same_database_read_only_plan(self):
        frozen = {'status': 'PASS', 'state': 'READ_ONLY_PLAN', 'databaseUuid': release.UUID,
            'database': release.DATABASE, 'port': 3306, 'issues': [], 'sourceSha256': 'e' * 64, 'planSha256': 'f' * 64}
        bound = self.validate(frozen=frozen)
        self.assertTrue(bound['sourceBound'])
        self.assertEqual(frozen['sourceSha256'], bound['sourceSha256'])
        for key, value in (('status', 'BLOCKED'), ('state', 'ACTIVE'), ('database', 'different'),
                           ('issues', ['SOURCE_MISMATCH']), ('sourceSha256', '')):
            with self.subTest(key=key), self.assertRaises(release.Blocked): self.validate(frozen={**frozen, key: value})

    def test_wrong_actual_backup_or_production_app_claim_is_rejected(self):
        for key, value in (('backupDatabaseSha256', 'f' * 64), ('applicationStarted', True),
                           ('sourceUnchanged', False), ('fullBaseTableCount', 2)):
            actual = {**self.actual, key: value}
            with self.subTest(key=key), self.assertRaises(release.Blocked):
                release.validate_restore_evidence(self.plan, self.backup, actual, self.raw, self.refs)


class PreDdlAbortContract(unittest.TestCase):
    def test_any_migration_attempt_or_native_stage_receipt_blocks_before_startup(self):
        cache = Path(__file__).resolve().parents[2] / '.cache'; cache.mkdir(exist_ok=True)
        for filename in ('operator-approval-migrate-45-synthetic.properties', 'writer-observation-migrate-45-synthetic.json',
                         'migration-45-receipt.json', 'carry-apply-receipt.json', 'carry-activate-receipt.json', 'carry-verify-receipt.json'):
            with tempfile.TemporaryDirectory(dir=cache) as temporary, mock.patch.object(release, 'current_matches'), \
                 mock.patch.object(release, 'startup') as start, mock.patch.object(release, 'install_crontab_checked') as cron:
                out = Path(temporary); (out / filename).write_text('synthetic only')
                with self.subTest(filename=filename), self.assertRaisesRegex(release.Blocked, 'ABORT_FORBIDDEN_AFTER_FINANCIAL_MIGRATION_ATTEMPT'):
                    release.abort_before_ddl({}, out)
                start.assert_not_called(); cron.assert_not_called()

    def test_changed_old_controls_and_financial_schema_failure_never_start_old_writer(self):
        cache = Path(__file__).resolve().parents[2] / '.cache'; cache.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=cache) as temporary:
            directory = Path(temporary); (directory / 'pre-ddl-controls.json').write_text('{"original":"value"}')
            for altered in (True, False):
                with contextlib.ExitStack() as stack:
                    stack.enter_context(mock.patch.object(release, 'current_matches'))
                    stack.enter_context(mock.patch.object(release, 'media_cron_directory', return_value=directory))
                    stack.enter_context(mock.patch.object(release, 'secure', side_effect=lambda p, **k: Path(p)))
                    stack.enter_context(mock.patch.object(release, 'pre_ddl_controls', return_value={'original': 'changed' if altered else 'value'}))
                    stack.enter_context(mock.patch.object(release, 'check_release'))
                    stack.enter_context(mock.patch.object(release, 'verify_pre_ddl_schema', side_effect=release.Blocked('PARTIAL_DDL_PRESENT')))
                    start = stack.enter_context(mock.patch.object(release, 'startup'))
                    cron = stack.enter_context(mock.patch.object(release, 'install_crontab_checked'))
                    with self.assertRaises(release.Blocked): release.abort_before_ddl({}, directory)
                    start.assert_not_called(); cron.assert_not_called()

    def test_safe_abort_verifies_actual_old_process_then_restores_only_original_cron(self):
        cache = Path(__file__).resolve().parents[2] / '.cache'; cache.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=cache) as temporary, contextlib.ExitStack() as stack:
            directory = Path(temporary); controls = {'files': {'synthetic': 'hash'}}
            (directory / 'pre-ddl-controls.json').write_text(json.dumps(controls))
            original = (release.MEDIA_ENTRY + '\n7 2 * * * /unrelated-job\n').encode()
            (directory / 'root-crontab-before.txt').write_bytes(original)
            spool = directory / 'spool'; spool.write_bytes(release.paused_crontab(original))
            plan = {'expected': {'sourceCommit': 'a' * 40, 'jarSha256': 'b' * 64}}
            for name in ('current_matches', 'check_release', 'verify_pre_ddl_schema'):
                stack.enter_context(mock.patch.object(release, name))
            stack.enter_context(mock.patch.object(release, 'ROOT_CRONTAB', spool))
            stack.enter_context(mock.patch.object(release, 'media_cron_directory', return_value=directory))
            stack.enter_context(mock.patch.object(release, 'secure', side_effect=lambda p, **k: Path(p)))
            stack.enter_context(mock.patch.object(release, 'pre_ddl_controls', return_value=controls))
            stack.enter_context(mock.patch.object(release, 'validate_media_cron', return_value={'expectedCrontabSha256': __import__('hashlib').sha256(original).hexdigest()}))
            stack.enter_context(mock.patch.object(release, 'media_process_ids', return_value=[]))
            order = []
            stack.enter_context(mock.patch.object(release, 'startup', side_effect=lambda: order.append('start')))
            verify = stack.enter_context(mock.patch.object(release, 'running_artifact_matches', side_effect=lambda p: order.append('actual-old-jar')))
            cron = stack.enter_context(mock.patch.object(release, 'install_crontab_checked', side_effect=lambda *a: order.append('restore-original-cron')))
            value = release.abort_before_ddl(plan, directory)
            self.assertEqual(['start', 'actual-old-jar', 'restore-original-cron'], order)
            self.assertFalse(value['databaseRestored']); self.assertFalse(value['configurationRestored'])
            self.assertFalse(value['financialDdlExecuted'])
            self.assertEqual(plan['expected'], verify.call_args.args[0]['candidate'])
            self.assertEqual(original, cron.call_args.args[1])


class PrivateAcceptanceContract(unittest.TestCase):
    def setUp(self):
        self.fixture=FourChannelReleaseContract();self.fixture.setUp()
        self.scope={'ownerId':self.fixture.owner,'nonce':'abcdef01','expiresAt':'2100-01-01T00:00:00Z','wechatCnyMinor':300}

    def funding(self):
        value=self.fixture.funding(release.CHANNELS);value['approvedTransactionLimit']=4;value['temporaryOffer']=self.scope.copy()
        for channel,quote in value['channelQuotes'].items():
            amount=300 if channel=='wechat_pay' else 1 if channel=='paypal' else 50
            quote.update(packageId='accept-abcdef01-'+channel+('-CNY' if channel=='wechat_pay' else '-USD'),
                         credits=1,maxPurchases=1,ownerId=self.fixture.owner,selectedPackageAmountMinor=amount,approvedMaximumMinor=amount)
        return value

    def test_explicit_private_quotes_are_one_approved_transaction_each_without_changing_public_amounts(self):
        release.validate_canary_funding(self.funding(),self.fixture.jar,release.CHANNELS)
        missing=self.funding();del missing['temporaryOffer']
        with self.assertRaises(release.Blocked):release.validate_canary_funding(missing,self.fixture.jar,release.CHANNELS)

    def test_private_identity_currency_price_credit_and_limit_cannot_be_substituted(self):
        for change in ({'packageId':'starter-USD'},{'ownerId':'another-owner'},{'credits':100},{'maxPurchases':2},
                       {'selectedPackageAmountMinor':1},{'approvedTransactionLimit':2},{'currency':'CNY'}):
            value=self.funding();value['channelQuotes']['card'].update(change)
            with self.subTest(change=change),self.assertRaises(release.Blocked):release.validate_canary_funding(value,self.fixture.jar,release.CHANNELS)

    def test_expired_or_unapproved_wechat_minimum_is_rejected(self):
        for change in ({'expiresAt':'2000-01-01T00:00:00Z'},{'nonce':'bad'},{'wechatCnyMinor':100},{'ownerId':'one,two'}):
            value=self.funding();value['temporaryOffer'].update(change)
            with self.subTest(change=change),self.assertRaises(release.Blocked):release.validate_canary_funding(value,self.fixture.jar,release.CHANNELS)

    def test_runtime_private_prices_require_single_owner_canary_and_are_forbidden_at_public_open(self):
        config=self.fixture.configuration(release.CHANNELS,canary=True)
        config.update({'qingfeng.payment.acceptance.enabled':'true','qingfeng.payment.acceptance.owner-id':self.fixture.owner,
                       'qingfeng.payment.acceptance.nonce':'abcdef01','qingfeng.payment.acceptance.expires-at':self.scope['expiresAt'],
                       'qingfeng.payment.acceptance.wechat-cny':'3.00'})
        release.validate_configuration(self.fixture.old,config,False,True,release.CHANNELS,self.fixture.paypal)
        with self.assertRaises(release.Blocked):release.validate_configuration(self.fixture.old,config,False,False,[],self.fixture.paypal)
        opened=self.fixture.configuration(release.CHANNELS,accepting=True)
        opened.update({k:v for k,v in config.items() if k.startswith('qingfeng.payment.acceptance.')})
        for enabled in ('true','false'):
            opened['qingfeng.payment.acceptance.enabled']=enabled
            with self.assertRaises(release.Blocked):release.validate_configuration(self.fixture.old,opened,True,False,release.CHANNELS,self.fixture.paypal)

    def business(self):
        value=self.fixture.business();value['temporaryOffer']=self.scope.copy()
        for channel,proof in value['channelAcceptance'].items():
            proof.update(packageId='accept-abcdef01-'+channel+('-CNY' if channel=='wechat_pay' else '-USD'),
                         credits=1,maxPurchases=1,ownerId=self.fixture.owner,orderCreatedAt='2026-11-13T10:00:00Z',
                         amountMinor=300 if channel=='wechat_pay' else 1 if channel=='paypal' else 50)
        return value

    def test_private_actual_acceptance_preserves_all_native_channel_requirements_and_order_snapshot(self):
        release.validate_business_acceptance(self.fixture.plan,self.business())
        for change in ({'walletType':''},{'credits':100},{'ownerId':'other'},{'packageId':'accept-deadbeef-apple_pay-USD'},
                       {'orderCreatedAt':'2100-01-01T00:00:00Z'},{'providerReadback':'NOT_TESTED'}):
            value=self.business();value['channelAcceptance']['apple_pay'].update(change)
            with self.subTest(change=change),self.assertRaises(release.Blocked):release.validate_business_acceptance(self.fixture.plan,value)

    def test_late_delivery_after_offer_expiry_can_accept_only_an_earlier_reserved_snapshot(self):
        value=self.business();value['temporaryOffer']['expiresAt']='2026-11-13T11:00:00Z'
        release.validate_business_acceptance(self.fixture.plan,value)
        value['channelAcceptance']['paypal']['orderCreatedAt']='2026-11-13T12:00:00Z'
        with self.assertRaises(release.Blocked):release.validate_business_acceptance(self.fixture.plan,value)


class ObservedProductionSyntaxContract(unittest.TestCase):
    def test_same_trimmed_value_duplicate_properties_preserve_the_effective_value(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'same.properties'
            p.write_text('scheduler.enabled = false\nscheduler.enabled=false\nflyway.enabled=false\nflyway.enabled = false \nflyway.enabled=false\nflyway.enabled=false\nsql.init=never\nsql.init = never\nsql.init=never\n')
            self.assertEqual({'scheduler.enabled':'false','flyway.enabled':'false','sql.init':'never'},release.property_map(p))

    def test_different_duplicate_values_and_continuations_still_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'different.properties'
            for body in ('a=false\na=true\n','a=false\na=FALSE\n','a=value\\\nnext=bad\n'):
                p.write_text(body)
                with self.subTest(body=body),self.assertRaises(release.Blocked):release.property_map(p)

    def test_reviewed_media_entry_is_only_the_exact_observed_command_with_redirection(self):
        expected='*/10 * * * * /usr/bin/python3 /opt/qingfeng-music/monitor/media_health_check.py 2>>/var/log/qingfeng-media-health.log'
        self.assertEqual(expected,release.MEDIA_ENTRY)
        for wrong in (expected.split(' 2>>')[0],expected+'; true',expected.replace('/usr/bin/python3','python3')):
            with self.subTest(wrong=wrong),self.assertRaises(release.Blocked):
                release.validate_media_cron({'mediaCron':{'entry':wrong,'expectedCrontabSha256':'a'*64,'scriptSha256':'b'*64}})

    def test_pausing_observed_media_command_preserves_every_other_line_verbatim(self):
        other='17 * * * * /usr/bin/python3 /opt/other.py 2>>/var/log/other.log'
        original=('MAILTO=root\n'+other+'\n'+release.MEDIA_ENTRY+'\n').encode()
        actual=release.paused_crontab(original)
        self.assertEqual(('MAILTO=root\n'+other+'\n'+release.MEDIA_PAUSE_MARKER+release.MEDIA_ENTRY+'\n').encode(),actual)


class NativeDumpCompatibilityContract(unittest.TestCase):
    def test_backup_invokes_supported_dump_options_and_keeps_process_timeout(self):
        with tempfile.TemporaryDirectory() as d, contextlib.ExitStack() as stack:
            out=Path(d)/'backup'
            for name in ('check_release','check_build_receipt','current_matches','stop_units'):
                stack.enter_context(mock.patch.object(release,name))
            stack.enter_context(mock.patch.object(release,'property_map',return_value={'spring.datasource.url':'jdbc:mysql://127.0.0.1:3306/qingfeng_music'}))
            stack.enter_context(mock.patch.object(release,'root_observation',return_value={'uuid':release.UUID,'schema':release.DATABASE,'port':3306}))
            stack.enter_context(mock.patch.object(release,'source_objects',return_value={}))
            dump=stack.enter_context(mock.patch.object(release.subprocess,'run',return_value=mock.Mock(returncode=7)))
            with self.assertRaisesRegex(release.Blocked,'DATABASE_DUMP_FAILED'):
                release.backup({'expected':{}},out)
            args=dump.call_args.args[0]
            self.assertEqual('mysqldump',args[0])
            self.assertNotIn('--connect-timeout=5',args)
            self.assertIn('--single-transaction',args)
            self.assertIn('--set-gtid-purged=OFF',args)
            self.assertEqual(300,dump.call_args.kwargs['timeout'])
            self.assertFalse((out/'backup-receipt.json').exists())


class RestoredDdlEquivalenceContract(unittest.TestCase):
    def test_only_redundant_matching_column_charset_is_normalized(self):
        a=b"CREATE TABLE `x` (\n  `a` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL,\n  `b` longtext COLLATE utf8mb4_bin\n)"
        b=a.replace(b' COLLATE',b' CHARACTER SET utf8mb4 COLLATE')
        self.assertEqual(a,release.canonical_ddl(b))
        self.assertEqual(a,release.canonical_ddl(a))
        self.assertNotEqual(a,release.canonical_ddl(b.replace(b'utf8mb4_unicode_ci',b'utf8mb4_bin')))
        self.assertNotEqual(a,release.canonical_ddl(b.replace(b'varchar(160)',b'varchar(159)')))
        other=b"  `a` varchar(10) CHARACTER SET latin1 COLLATE latin1_bin DEFAULT ' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'"
        self.assertEqual(other,release.canonical_ddl(other))


if __name__ == '__main__':
    unittest.main()
