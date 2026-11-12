<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { platform } from '../../../platform'
import { locale, t } from '../../../shared/i18n'
import { accountState } from '../../../shared/identity/account-state'
import { creditBalance, creditsEnabled, refreshCreditBalance } from '../../../shared/identity/credit-balance'
import { logtoState } from '../../../shared/identity/logto-state'
import { maskedPhoneLabel } from '../../../shared/identity/phone-label'
import NavIcon from '../../../shared/ui/NavIcon.vue'

const phone = computed(() => logtoState.value.authenticated
  ? maskedPhoneLabel(logtoState.value.phoneMasked)
  : '')
const email = computed(() => {
  const musicEmail = logtoState.value.authenticated ? logtoState.value.email : undefined
  const googleEmail = accountState.value.authenticated ? accountState.value.email : undefined
  const match = (musicEmail || googleEmail)?.match(/^([^@])[^@]*@([^@]+)$/u)
  return match ? `${match[1]}•••@${match[2]}` : ''
})
const balance = computed(() => creditsEnabled.value && creditBalance.value !== null
  ? new Intl.NumberFormat(locale.value).format(creditBalance.value)
  : '')

// The web shell loads the shared balance. The native shell does not show a balance in its header.
onMounted(() => {
  if (platform.kind !== 'web') {
    void refreshCreditBalance(accountState.value.authenticated || logtoState.value.authenticated)
  }
})
</script>

<template>
  <div class="profile-account">
    <section class="profile-account-card" aria-labelledby="profile-account-heading">
      <h2 id="profile-account-heading"><NavIcon name="recovery" />{{ t('账号与安全') }}</h2>
      <dl class="profile-account-contacts">
        <div class="profile-account-row">
          <dt>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M21 16.5v2a2 2 0 0 1-2.2 2 16.5 16.5 0 0 1-7.2-2.6 16 16 0 0 1-5-5A16.5 16.5 0 0 1 4 5.7 2 2 0 0 1 6 3.5h2a2 2 0 0 1 2 1.7l.6 2.5a2 2 0 0 1-.6 2.1l-1 1a12 12 0 0 0 4.7 4.7l1-1a2 2 0 0 1 2.1-.6l2.5.6a2 2 0 0 1 1.7 2Z" />
            </svg>
            {{ t('手机号') }}
          </dt>
          <dd>{{ phone || t('未提供') }}</dd>
        </div>
        <div class="profile-account-row">
          <dt>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="m4 7 8 6 8-6" />
            </svg>
            {{ t('邮箱') }}
          </dt>
          <dd>{{ email || t('未提供') }}</dd>
        </div>
      </dl>
      <RouterLink class="profile-account-row profile-account-link" to="/account">
        <span><NavIcon name="account" />{{ t('账号与登录') }}</span>
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 4 4 4-4 4" /></svg>
      </RouterLink>
    </section>

    <section class="profile-account-card profile-benefits" aria-labelledby="profile-benefits-heading">
      <h2 id="profile-benefits-heading">
        <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="m2.5 6.6 5.3 3.3L12 4l4.2 5.9 5.3-3.3L19 18H5L2.5 6.6ZM5 19.5h14V21H5z" /></svg>
        {{ t('我的权益') }}
      </h2>
      <div class="profile-credits">
        <span class="profile-credit-icon" aria-hidden="true"><NavIcon name="create" /></span>
        <div class="profile-credit-copy">
          <strong>{{ t('创作积分') }}</strong>
          <p v-if="balance">{{ t('可用积分') }} <b>{{ balance }}</b></p>
        </div>
        <RouterLink class="profile-recharge" to="/recharge">{{ t('去充值') }}</RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.profile-account { display: grid; align-content: start; gap: 12px; min-width: 0; }
.profile-account-card {
  min-width: 0;
  padding: 14px 18px 8px;
  border: 1px solid var(--profile-line, var(--line));
  border-radius: 16px;
  background: var(--profile-card, var(--surface-glass));
  color: var(--ink);
  box-shadow: var(--profile-shadow, 0 2px 10px #71444f08);
}
.profile-account-card h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 750;
  line-height: 1.35;
}
.profile-account-card svg { width: 16px; height: 16px; flex: none; }
.profile-account-card h2 svg { width: 17px; height: 17px; color: #b7442b; }
.profile-account-contacts { margin: 0; }
.profile-account-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 34px;
  padding-block: 5px;
  border-bottom: 1px solid #ecdde1a0;
  font-size: 13px;
  line-height: 1.35;
}
.profile-account-row dt, .profile-account-row span { display: flex; align-items: center; gap: 9px; }
.profile-account-row dt { flex: 0 0 auto; }
.profile-account-row dd {
  min-width: 0;
  margin: 0;
  color: var(--muted);
  text-align: end;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
.profile-account-link { border: 0; color: inherit; text-decoration: none; }
.profile-account-link:hover { color: var(--accent-text); }
.profile-benefits { padding-bottom: 10px; }
.profile-benefits h2 svg { color: #d47819; }
.profile-credits {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
  padding: 8px 10px;
  border-radius: 11px;
  background: linear-gradient(105deg, #fff8eb 10%, #ffede5 100%);
}
.profile-credit-icon {
  display: inline-grid;
  width: 36px;
  height: 36px;
  flex: none;
  place-items: center;
  border: 1px solid #fae1b5;
  border-radius: 50%;
  background: linear-gradient(140deg, #fff3cd 10%, #ffbd63);
  color: #8e4a0c;
}
.profile-credit-icon svg { width: 18px; height: 18px; }
.profile-credit-copy { flex: 1; min-width: 0; }
.profile-credit-copy strong { display: block; font-size: 13px; line-height: 1.3; }
.profile-credit-copy p {
  margin: 3px 0 0;
  color: #756065;
  font-size: 12px;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
}
.profile-recharge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 33px;
  padding: 6px 13px;
  border-radius: 99px;
  background: #ce3926;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
}
.profile-recharge:hover { background: #ae2e20; }
@media (max-width: 600px) {
  .profile-account-link, .profile-recharge { min-height: 44px; }
}
</style>
