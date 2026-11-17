<script setup lang="ts">
import { ref } from 'vue'
import { t } from '../../../shared/i18n'
import { requestJson, ApiError } from '../../../shared/http/api-client'
import { refreshLogtoState } from '../../../shared/identity/logto-state'
import { refreshCreditBalance } from '../../../shared/identity/credit-balance'
import { nativeLoginError } from '../../identity/model/native-login'
const emit = defineEmits<{ bound: [] }>()
const phone = ref(''), code = ref(''), busy = ref(false), sent = ref(false), message = ref(''), availableAt = ref(0)
async function submit(send: boolean) {
  if (busy.value) return
  const number = phone.value.trim().replace(/[ ()-]/g, '')
  if (!/^\+[1-9][0-9]{7,14}$/.test(number)) { message.value = t('请输入含国家区号的手机号，例如 +8613800000000'); return }
  if (send && Date.now() < availableAt.value) { message.value = t('请等待60秒后重新发送'); return }
  busy.value = true; message.value = ''
  try {
    if (send) {
      await requestJson('public/auth/native/code', 'POST', { channel: 'phone', identifier: number, purpose: 'bind' })
      sent.value = true; availableAt.value = Date.now() + 60000; message.value = t('验证码已发送')
    } else {
      await requestJson('public/auth/native/phone/bind', 'POST', { phone: number, code: code.value })
      await refreshLogtoState(); await refreshCreditBalance(true); emit('bound')
    }
  } catch (error) { message.value = t(error instanceof ApiError && error.code === 'LOGIN_ACCOUNT_CONFLICT' ? '该手机号或当前账号已绑定，请使用原有登录方式。' : nativeLoginError(error instanceof ApiError ? error.code : undefined)) }
  finally { busy.value = false }
}
</script>
<template>
  <form class="phone-binding" @submit.prevent="submit(false)">
    <p>{{ t('首次绑定手机号成功赠送50积分，每个账号仅限一次。') }}</p>
    <label>{{ t('手机号') }}<input v-model="phone" type="tel" autocomplete="tel" placeholder="+86 13800000000" :disabled="busy" required @input="sent = false"></label>
    <button type="button" :disabled="busy" @click="submit(true)">{{ t('发送验证码') }}</button>
    <label>{{ t('验证码') }}<input v-model="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" :disabled="busy" required></label>
    <p role="status">{{ message }}</p>
    <button type="submit" :disabled="busy || !sent">{{ t('确认绑定并领取50积分') }}</button>
  </form>
</template>
<style scoped>
.phone-binding { display: grid; gap: 12px; padding: 16px 0; border-top: 1px solid var(--line); }
label { display: grid; gap: 6px; } input, button { padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--ink); } button { cursor: pointer; color: #cf3525; } button:disabled { opacity: .5; cursor: default; } p { margin: 0; font-size: 13px; }
</style>
