<script setup lang="ts">
import { t } from '../../../shared/i18n/index'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import { usePlayer } from '../../../shared/player/store'
const router = useRouter(), player = usePlayer(), busy = ref(true), configured = ref(false), secret = ref(''), restoreCode = ref(''), error = ref(''), notice = ref('')
onMounted(async () => { try { await ensureCreator(); configured.value = (await requestJson<{configured: boolean}>('app/identity/recovery')).configured } catch (e) { error.value = e instanceof Error ? e.message : '暂时无法读取身份状态' } finally { busy.value = false } })
async function issue() {
  if (configured.value && !window.confirm(t('重新生成会立即停用之前的找回码，是否继续？'))) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await ensureCreator(); secret.value = (await requestJson<{code: string}>('app/identity/recovery', 'POST', {})).code; configured.value = true; notice.value = '请保存到自己的密码管理器或下载留存。找回码仅显示这一次，使用后失效。' }
  catch (e) { error.value = e instanceof Error ? e.message : '生成找回码失败' } finally { busy.value = false }
}
function download() {
  const url = URL.createObjectURL(new Blob([`${t('青风音乐作品找回码')}\n${secret.value}\n\n${t('请保密保存。此码可找回我的音乐，使用后失效。重新生成会停用旧码。')}\n`], {type:'text/plain;charset=utf-8'}))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = t('青风音乐-作品找回码.txt'); anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function copy() { try { await navigator.clipboard.writeText(secret.value); notice.value = '找回码已复制，请保密保存。' } catch { notice.value = '请手动复制，或下载找回码文件。' } }
async function recover() {
  if (!restoreCode.value.trim() || !window.confirm(t('将打开找回码对应的音乐收藏。当前作品不会合并，请先保存自己的找回码，是否继续？'))) return
  busy.value = true; error.value = ''
  try { await ensureCreator(); await requestJson('public/session/recover', 'POST', {code:restoreCode.value.trim()}); player.close(); secret.value = ''; restoreCode.value = ''; await router.replace('/works') }
  catch (e) { error.value = e instanceof Error ? e.message : '恢复失败，请检查找回码' } finally { busy.value = false }
}
</script>
<template>
  <p class="backend-state">{{ t('找回我的音乐') }}</p><h1>{{ t('让每一份心意，都能被找回。') }}</h1>
  <p class="detail-muted">{{ t('换设备前，记得保存作品找回码。凭找回码，你可以继续查看和编辑自己的音乐。') }}</p>
  <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
  <div class="identity-grid">
    <section class="detail-panel"><h2>{{ t('保存作品找回码') }}</h2><p class="detail-muted">{{ configured ? t("你已设置找回码。如未保存，可以重新生成并使旧码失效。") : t("尚未设置找回码，请在清理浏览器或更换设备前保存。") }}</p><button class="button" :disabled="busy" @click="issue">{{ configured ? t("重新生成找回码") : t("生成找回码") }}</button><template v-if="secret"><label class="field">{{ t('找回码（仅本次显示）') }}<textarea :value="secret" readonly rows="2" @focus="($event.target as HTMLTextAreaElement).select()"></textarea></label><div class="actions"><button class="button secondary" @click="copy">{{ t('复制') }}</button><button class="button secondary" @click="download">{{ t('下载留存') }}</button></div></template><p class="save-info">{{ t('找回码可访问你的私人作品，请只交给自己保管。礼物分享链接不能替代找回码。') }}</p></section>
    <section class="detail-panel"><h2>{{ t('找回已有作品') }}</h2><form @submit.prevent="recover"><label class="field">{{ t('输入之前保存的找回码') }}<input v-model="restoreCode" class="recovery-input" :disabled="busy" maxlength="100" autocomplete="off" spellcheck="false" :placeholder="t('粘贴你的作品找回码')"></label><button class="button secondary" :disabled="busy || !restoreCode.trim()">{{ t('找回我的音乐') }}</button></form><p class="save-info">{{ t('找回码只能使用一次，恢复后请生成并保存新的找回码。多个身份的作品不会自动合并。') }}</p></section>
  </div><RouterLink class="detail-back" to="/works">{{ t('← 返回我的音乐') }}</RouterLink>
</template>
