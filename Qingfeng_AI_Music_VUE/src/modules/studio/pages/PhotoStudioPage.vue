<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { currentCreatorId, ensureCreator } from '../../../shared/http/api-client'
import { getSystemInfo } from '../../../shared/http/system'
import { locale, t } from '../../../shared/i18n'
import { useCredits } from '../../../shared/credits/useCredits'
import { guardCredits } from '../../../shared/credits/credit-gate'
import CreditCostHint from '../../../shared/credits/CreditCostHint.vue'
import { saveWork } from '../api/works'
import { generatePhotoLyrics, getLyricsRequest } from '../../works/api/detail'

interface PhotoDraft {
  id: string
  key: string
  note: string
  language: string
  submitted: boolean
}

const router = useRouter()
const photo = ref<File>()
const preview = ref('')
const note = ref('')
const language = ref<string>(locale.value)
const ready = ref(false)
const busy = ref(false)
const checking = ref(false)
const needsCheck = ref(false)
const outcomeUnknown = ref(false)
const locked = ref(false)
const error = ref('')
const creditShort = ref(false)
const { credits, refreshCredits } = useCredits()
let owner = ''
let draft: PhotoDraft | undefined
let alive = true

const storageKey = () => `qingfeng.photo-draft:${owner}`
function remember() {
  try {
    if (draft) sessionStorage.setItem(storageKey(), JSON.stringify(draft))
    else sessionStorage.removeItem(storageKey())
  } catch { /* The current page still holds the request key. */ }
}

function restore() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey()) || 'null') as PhotoDraft | null
    if (!saved || !/^[a-f0-9-]{36}$/i.test(saved.id) || !/^[a-f0-9-]{36}$/i.test(saved.key)
      || typeof saved.note !== 'string' || saved.note.length > 300 || !['zh-CN','en','mixed'].includes(saved.language)
      || typeof saved.submitted !== 'boolean') return
    draft = saved
    locked.value = true
    note.value = saved.note
    language.value = saved.language
    needsCheck.value = saved.submitted
  } catch { /* A corrupt browser draft does not block creation. */ }
}

function clearPhoto() {
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  photo.value = undefined
}

function choosePhoto(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0]
  input.value = ''
  if (!selected) return
  error.value = ''
  if (!['image/jpeg','image/png'].includes(selected.type) || !selected.size || selected.size > 5 * 1024 * 1024) {
    error.value = '请选择 5 MB 以内的 JPG 或 PNG 照片。'
    return
  }
  clearPhoto()
  photo.value = selected
  preview.value = URL.createObjectURL(selected)
}

async function openLyrics(revisionId: string, workId: string, lyricLanguage: string) {
  if (!alive || currentCreatorId() !== owner) return
  const failure = await router.push({ path: `/works/${workId}`, query: { revision: revisionId, language: lyricLanguage } })
  if (!failure) {
    draft = undefined
    remember()
  }
}

async function checkOriginal() {
  if (!draft || checking.value || currentCreatorId() !== owner) return
  checking.value = true
  error.value = ''
  try {
    const state = await getLyricsRequest(draft.id, draft.key)
    if (!alive || currentCreatorId() !== owner) return
    if (state.status === 'SUCCEEDED' && state.revisionId) {
      await openLyrics(state.revisionId, draft.id, draft.language)
    } else if (state.status === 'PENDING' || state.status === 'UNKNOWN') {
      outcomeUnknown.value = state.status === 'UNKNOWN'
      error.value = '歌词结果还在确认中，请稍后核对进度。'
    } else {
      needsCheck.value = false
      draft.submitted = false
      if (state.status === 'FAILED') {
        draft.key = crypto.randomUUID()
        error.value = '上次未生成歌词，可以重新提交。'
      }
      remember()
    }
  } catch (cause) {
    if (alive) error.value = cause instanceof Error ? cause.message : '暂时无法核对歌词进度。'
  } finally {
    checking.value = false
  }
}

async function submit() {
  if (!ready.value || busy.value || needsCheck.value || !photo.value) return
  const image = photo.value
  busy.value = true
  error.value = ''
  creditShort.value = false
  try {
    await ensureCreator()
    if (!alive || currentCreatorId() !== owner) throw new Error('账号已切换，请刷新后继续。')
    const gate = await guardCredits(() => credits.value, refreshCredits)
    if (!alive || currentCreatorId() !== owner) return
    if (gate.blocked) {
      creditShort.value = true
      error.value = t('积分不足，当前余额 {balance} 积分，生成需要 {cost} 积分。', {balance:gate.balance as number,cost:gate.cost})
      return
    }
    draft ??= { id: crypto.randomUUID(), key: crypto.randomUUID(), note: note.value.trim(), language: language.value, submitted: false }
    locked.value = true
    remember()
    const work = await saveWork({ recipient: '', occasion: '', story: draft.note, message: '',
      style: '自动推荐', title: '照片创作', vocalGender: 'AUTO' }, undefined, draft.id)
    if (!alive || currentCreatorId() !== owner) return
    draft.submitted = true
    remember()
    const revision = await generatePhotoLyrics(work.id, work.version, draft.key, draft.language, image)
    await openLyrics(revision.id, work.id, draft.language)
    if (alive && draft) needsCheck.value = true
  } catch (cause) {
    if (!alive) return
    needsCheck.value = draft?.submitted === true
    error.value = cause instanceof Error ? cause.message : '照片歌词生成失败，请稍后再试。'
  } finally {
    busy.value = false
    if (alive) void refreshCredits().catch(() => {})
  }
}

function reset() {
  draft = undefined
  locked.value = false
  needsCheck.value = false
  outcomeUnknown.value = false
  error.value = ''
  remember()
}

onMounted(async () => {
  try {
    const system = await getSystemInfo()
    if (!system.capabilities.photoLyrics) throw new Error('照片写歌暂不可用，请稍后再试。')
    await ensureCreator()
    if (!alive) return
    owner = currentCreatorId()
    restore()
    ready.value = true
    void refreshCredits().catch(() => {})
    if (needsCheck.value) await checkOriginal()
  } catch (cause) {
    if (alive) error.value = cause instanceof Error ? cause.message : '照片写歌暂不可用，请稍后再试。'
  }
})
onBeforeUnmount(() => {
  alive = false
  clearPhoto()
})
</script>

<template>
  <section class="photo-studio" aria-labelledby="photo-studio-title">
    <RouterLink class="detail-back" to="/create">{{ t('← 返回开始创作') }}</RouterLink>
    <h1 id="photo-studio-title">{{ t('用照片写一首歌') }}</h1>
    <p>{{ t('选一张想写进歌里的照片，也可以告诉我们它背后的故事。') }}</p>
    <form class="photo-form" :aria-busy="busy||checking" @submit.prevent="submit">
      <div class="photo-frame">
        <img v-if="preview" :src="preview" :alt="t('照片预览')">
        <label class="photo-picker">
          <input type="file" accept="image/jpeg,image/png" :disabled="!ready||busy||checking||needsCheck" @change="choosePhoto">
          <span>{{ t(preview?'更换照片':'选择照片') }}</span>
        </label>
        <small>{{ t('一张 JPG 或 PNG，最大 5 MB') }}</small>
      </div>
      <div class="photo-fields">
        <label class="field">
          {{ t('照片背后的故事（选填）') }}
          <textarea v-model="note" rows="4" maxlength="300" :disabled="busy||locked" :placeholder="t('例如：这是妈妈第一次看到海。')"/>
        </label>
        <label class="field">
          {{ t('歌词语言') }}
          <select v-model="language" :disabled="busy||locked">
            <option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option>
            <option value="mixed">{{ t('中英混合') }}</option>
          </select>
        </label>
        <small>{{ t('照片会发送给 AI 用于本次创作。分享作品时不会公开原图。') }}</small>
        <p v-if="error" class="notice error" role="alert">{{ t(error) }}
          <RouterLink v-if="creditShort" to="/recharge">{{ t('去充值') }}</RouterLink>
        </p>
        <div class="photo-actions">
          <button v-if="needsCheck" class="button primary" type="button" :disabled="checking||busy" @click="checkOriginal">
            {{ t(checking?'正在核对歌词进度…':'核对歌词进度') }}
          </button>
          <button v-else class="button primary" type="submit" :disabled="!ready||!photo||busy">
            {{ t(busy?'正在准备照片并编写歌词…':'生成歌词') }}
          </button>
          <CreditCostHint v-if="credits&&credits.lyricsCost>0&&!needsCheck" :cost="credits.lyricsCost" :balance="credits.balance" :busy="busy"/>
        </div>
        <button v-if="locked&&!busy&&!needsCheck" type="button" class="text-button" @click="reset">{{ t('修改创作素材') }}</button>
        <button v-if="outcomeUnknown&&!checking" type="button" class="text-button" @click="reset">{{ t('开始新的照片创作') }}</button>
        <p class="photo-next">{{ t('下一步可以编辑歌词和音乐方向，确认后再生成歌曲。') }}</p>
      </div>
    </form>
  </section>
</template>

<style scoped>
.photo-studio { max-width:940px; margin:32px auto 64px; padding:0 20px; color:#302730; }
h1 { margin:20px 0 8px; font-size:clamp(30px, 5vw, 46px); line-height:1.12; }
.photo-studio > p { margin:0 0 24px; color:#69575c; }
.photo-form { display:grid; grid-template-columns:minmax(0,1.15fr) minmax(0,1fr); gap:24px; padding:clamp(14px,3vw,28px); background:#fffdfd; border:1px solid #efdee3; border-radius:22px; }
.photo-frame { display:flex; min-height:250px; flex-direction:column; align-items:center; justify-content:center; gap:12px; padding:16px; background:#fff0ed; border:1px dashed #c38680; border-radius:12px; }
.photo-frame img { max-width:100%; max-height:330px; object-fit:contain; border:6px solid #fffdfd; box-shadow:0 2px 14px #5b202322; }
.photo-picker { position:relative; display:inline-grid; place-items:center; min-height:44px; padding:0 18px; border:1px solid #bf5e53; border-radius:9px; background:#fffdfd; color:#a42c23; font-weight:650; }
.photo-picker input { position:absolute; inset:0; width:100%; height:100%; opacity:0; cursor:pointer; }
.photo-picker:focus-within { outline:2px solid #a42c23; outline-offset:3px; }
.photo-picker:has(input:disabled) { opacity:.55; }
.photo-fields { display:grid; align-content:start; gap:12px; }
.photo-fields textarea,.photo-fields select { width:100%; }
.photo-actions { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
small,.photo-next { color:#69575c; }
.photo-next { margin:0; font-size:14px; }
@media(max-width:670px) {
  .photo-studio { margin-top:20px; padding:0 14px; }
  .photo-form { grid-template-columns:1fr; gap:16px; }
  .photo-frame { min-height:175px; }
  .photo-frame img { max-height:230px; }
}
</style>
