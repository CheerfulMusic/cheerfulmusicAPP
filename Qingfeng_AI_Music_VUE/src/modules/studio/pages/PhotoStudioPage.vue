<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { currentCreatorId, ensureCreator } from '../../../shared/http/api-client'
import { getPhotoLyricsAvailable } from '../../../shared/http/system'
import { locale, t } from '../../../shared/i18n'
import { useCredits } from '../../../shared/credits/useCredits'
import { guardCredits } from '../../../shared/credits/credit-gate'
import CreditCostHint from '../../../shared/credits/CreditCostHint.vue'
import { saveWork } from '../api/works'
import { generatePhotoLyrics, getLyricsRequest } from '../../works/api/detail'
import { canSelectPhoto, maxPhotoCount, preparePhoto } from '../model/photo-upload'

interface PhotoDraft {
  id: string
  key: string
  note: string
  language: string
  submitted: boolean
}

const props = defineProps<{ embedded?: boolean }>()
const emit = defineEmits<{ created: [result: { workId: string; revisionId: string; language: string }]; close: []; recharge: [] }>()
const router = useRouter()
const photos = ref<{ file: File; preview: string }[]>([])
const preparing = ref(false)
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
const canEditPhotos = computed(() => ready.value && !busy.value && !preparing.value && !checking.value && !needsCheck.value)
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

function removePhoto(index: number) {
  if (!canEditPhotos.value) return
  const [removed] = photos.value.splice(index, 1)
  if (removed) URL.revokeObjectURL(removed.preview)
}

async function choosePhotos(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files ?? [])
  input.value = ''
  if (!selected.length || !canEditPhotos.value) return
  error.value = ''
  creditShort.value = false
  if (photos.value.length + selected.length > maxPhotoCount) {
    error.value = '最多可选 5 张照片，请减少本次选择的数量。'
    return
  }
  if (!selected.every(canSelectPhoto)) {
    error.value = '请选择 10 MB 以内的 JPG 或 PNG 照片。'
    return
  }
  preparing.value = true
  try {
    for (const original of selected) {
      try {
        const file = await preparePhoto(original)
        if (!alive) return
        photos.value.push({ file, preview: URL.createObjectURL(file) })
      } catch {
        if (!alive) return
        error.value = '部分照片无法处理，请更换照片后重试。'
      }
    }
  } finally {
    preparing.value = false
  }
}

async function openLyrics(revisionId: string, workId: string, lyricLanguage: string) {
  if (!alive || currentCreatorId() !== owner) return
  if (props.embedded) {
    draft = undefined; remember()
    emit('created', { workId, revisionId, language: lyricLanguage })
    return
  }
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
  if (!canEditPhotos.value || !photos.value.length) return
  const files = photos.value.map(photo => photo.file)
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
    const revision = await generatePhotoLyrics(work.id, work.version, draft.key, draft.language, files)
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
    if (!(await getPhotoLyricsAvailable())) throw new Error('照片写歌暂不可用，请稍后再试。')
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
  for (const photo of photos.value) URL.revokeObjectURL(photo.preview)
})
</script>

<template>
  <section class="photo-studio" aria-labelledby="photo-studio-title">
    <button v-if="props.embedded" type="button" class="detail-back" @click="emit('close')">{{ t('返回对话') }}</button><RouterLink v-else class="detail-back" to="/create">{{ t('← 返回开始创作') }}</RouterLink>
    <h1 id="photo-studio-title">{{ t('用照片写一首歌') }}</h1>
    <p>{{ t('选几张想写进歌里的照片，也可以告诉我们背后的故事。') }}</p>
    <form class="photo-form" :aria-busy="busy||checking||preparing" @submit.prevent="submit">
      <div class="photo-frame">
        <ol v-if="photos.length" class="photo-previews">
          <li v-for="(photo, index) in photos" :key="photo.preview">
            <img :src="photo.preview" :alt="t('第 {number} 张照片', { number: index + 1 })">
            <button type="button" class="photo-remove" :disabled="!canEditPhotos"
              :aria-label="t('删除第 {number} 张照片', { number: index + 1 })" @click="removePhoto(index)">×</button>
          </li>
        </ol>
        <label class="photo-picker">
          <input type="file" accept="image/jpeg,image/png" multiple
            :disabled="!canEditPhotos||photos.length>=maxPhotoCount" @change="choosePhotos">
          <span>{{ t(photos.length?'添加照片':'选择照片') }}</span>
        </label>
        <small>{{ t('1–5 张 JPG 或 PNG，单张原图最大 10 MB') }}</small>
        <small v-if="preparing || photos.length" role="status">
          {{ preparing ? t('正在处理照片…') : t('已选 {count}/{max} 张', { count: photos.length, max: maxPhotoCount }) }}
        </small>
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
          <button v-if="creditShort && props.embedded" type="button" @click="emit('recharge')">{{ t('去充值') }}</button><RouterLink v-else-if="creditShort" to="/recharge">{{ t('去充值') }}</RouterLink>
        </p>
        <div class="photo-actions">
          <button v-if="needsCheck" class="button primary" type="button" :disabled="checking||busy" @click="checkOriginal">
            {{ t(checking?'正在核对歌词进度…':'核对歌词进度') }}
          </button>
          <button v-else class="button primary" type="submit" :disabled="!canEditPhotos||!photos.length">
            {{ t(busy?'正在准备照片并编写歌词…':'生成歌词') }}
          </button>
          <small v-if="props.embedded && credits && credits.lyricsCost > 0 && !needsCheck">{{ t('本次生成消耗 {cost} 积分', { cost: credits.lyricsCost }) }}</small><CreditCostHint v-else-if="credits&&credits.lyricsCost>0&&!needsCheck" :cost="credits.lyricsCost" :balance="credits.balance" :busy="busy"/>
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
.photo-previews { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:8px; width:100%; margin:0; padding:0; list-style:none; }
.photo-previews li { position:relative; min-width:0; aspect-ratio:1; }
.photo-previews img { display:block; width:100%; height:100%; object-fit:cover; border:3px solid #fffdfd; border-radius:8px; }
.photo-remove { position:absolute; top:4px; right:4px; min-width:32px; min-height:32px; padding:0; border:1px solid #efdee3; border-radius:50%; background:#fffdfd; color:#302730; font-size:20px; line-height:1; }
.photo-frame small { text-align:center; }
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
}
</style>
