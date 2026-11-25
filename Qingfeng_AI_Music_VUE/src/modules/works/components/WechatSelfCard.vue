<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { platform } from '../../../platform'
import { ApiError, ensureCreator, getJson, requestJson } from '../../../shared/http/api-client'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { t } from '../../../shared/i18n'

const props = defineProps<{ path: string; embedded?: boolean }>()
const endpoint = 'app/wechat-card'
type State = 'BIND_REQUIRED' | 'NEEDS_MESSAGE' | 'BUSY' | 'SENDING' | 'SENT' | 'REJECTED' | 'UNAVAILABLE' | 'UNKNOWN'
type Challenge = { command: string; expiresAt: string }
const account = ref('')
const busy = ref(false), bound = ref(false), needsLogin = ref(false), checkResult = ref(false)
const challenge = ref<Challenge>()
const message = ref('')
let requestId: string | undefined
const buttonText = computed(() => checkResult.value ? '查询发送结果'
  : challenge.value ? '我已发验证码，发送卡片'
  : '发到我的微信')

onMounted(async () => {
  if (platform.kind !== 'web' || /MicroMessenger|WindowsWechat|MacWechat|\bWeChat\//i.test(navigator.userAgent)) return
  try {
    const availability = await getJson<{ accountName: string }>('wechat/card')
    if (typeof availability?.accountName === 'string') account.value = availability.accountName
  } catch {
    // This entry is optional on sites without an official-account callback.
  }
})

function reportError(error: unknown) {
  needsLogin.value = error instanceof ApiError && error.status === 401
  if (needsLogin.value) message.value = t('请先登录青风音乐，再发送歌曲卡片。')
  else if (error instanceof ApiError && error.status === 404) message.value = t('歌曲页或微信卡片发送暂不可用。')
  else if (requestId) {
    checkResult.value = true
    message.value = t('发送结果暂未确认，请先查看电脑微信。')
  } else message.value = t('微信卡片暂时无法发送，请稍后再试。')
}

async function send() {
  if (busy.value) return
  busy.value = true; message.value = ''; needsLogin.value = false
  try {
    const binding = await getJson<{ bound: boolean }>(endpoint + '/binding')
    bound.value = binding.bound
    await ensureCreator()
    if (!binding.bound && !requestId) {
      if (!challenge.value || Date.parse(challenge.value.expiresAt) <= Date.now())
        challenge.value = await requestJson<Challenge>(endpoint + '/binding', 'POST', {})
      return
    }
    requestId ??= crypto.randomUUID()
    const result = await requestJson<{ state: State }>(endpoint + '/send', 'POST', { requestId, path: props.path }, 16000)
    if (result.state === 'BIND_REQUIRED') {
      bound.value = false
      requestId = undefined
      checkResult.value = false
      challenge.value = await requestJson<Challenge>(endpoint + '/binding', 'POST', {})
      return
    }
    challenge.value = undefined
    checkResult.value = result.state === 'SENDING' || result.state === 'UNKNOWN'
    if (!checkResult.value && result.state !== 'BUSY') requestId = undefined
    switch (result.state) {
      case 'SENT':
        message.value = t('卡片已发到你的微信。请在电脑微信打开后转发给好友或分享到朋友圈。')
        break
      case 'NEEDS_MESSAGE':
        message.value = t('请给公众号「{account}」发送「分享卡片」，再回来点发送。', { account: account.value })
        break
      case 'BUSY':
      case 'SENDING':
        message.value = t('有卡片正在发送，请稍后再试。')
        break
      case 'UNKNOWN':
        message.value = t('发送结果暂未确认，请先查看电脑微信。')
        break
      default:
        message.value = t('微信卡片暂时无法发送，请稍后再试。')
    }
  } catch (error) { reportError(error) }
  finally { busy.value = false }
}

async function unlink() {
  if (busy.value) return
  busy.value = true
  try {
    await ensureCreator()
    await requestJson(endpoint + '/binding', 'DELETE')
    bound.value = false
    challenge.value = undefined
    requestId = undefined
    checkResult.value = false
    message.value = t('微信绑定已解除。')
  } catch (error) { reportError(error) }
  finally { busy.value = false }
}
</script>

<template>
  <div v-if="account" class="wechat-self-card">
    <button type="button" class="wechat-self-send" :disabled="busy" @click="send">
      {{ t(busy ? '正在处理…' : buttonText) }}
    </button>
    <button v-if="bound" type="button" class="wechat-self-unlink" :disabled="busy" @click="unlink">{{ t('解除微信绑定') }}</button>
    <div v-if="challenge" class="wechat-self-guide" aria-live="polite">
      <label>
        {{ t('在电脑微信给公众号「{account}」发送这条验证码。', { account }) }}
        <input :value="challenge.command" readonly spellcheck="false" @focus="($event.target as HTMLInputElement).select()">
      </label>
      <p>{{ t('验证码 10 分钟内有效。发完后回到这里点发送。') }}</p>
    </div>
    <p v-if="message" class="wechat-self-guide" role="status">{{ message }}</p>
    <template v-if="needsLogin">
      <a v-if="embedded" href="/account" target="_blank" rel="noopener">{{ t('登录青风音乐') }}</a>
      <button v-else type="button" class="wechat-self-unlink" @click="showLoginDialog">{{ t('登录青风音乐') }}</button>
    </template>
  </div>
</template>
