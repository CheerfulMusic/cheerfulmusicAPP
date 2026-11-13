"""Offline negative-path checks. No network, MySQL, systemd or production files."""
import importlib.util
import contextlib
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


if __name__ == '__main__':
    unittest.main()
