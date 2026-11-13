<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { currentCreatorId, ensureCreator } from '../../../shared/http/api-client'
import { getPhotoLyricsAvailable } from '../../../shared/http/system'
import { locale, t } from '../../../shared/i18n'
import { useCredits } from '../../../shared/credits/useCredits'
import { guardCredits } from '../../../shared/credits/credit-gate'
import CreditCostHint from '../../../shared/credits/CreditCostHint.vue'
import NavIcon from '../../../shared/ui/NavIcon.vue'
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
const storyInput = ref<HTMLTextAreaElement>()
const storyLimit = 300
const photoExamples = ['sea', 'mountains', 'tram', 'blossoms', 'cat']
const inspiration = [
  { label: '第一次看海', icon: 'landscape', story: '这是我第一次看海。天快黑了，浪一遍遍涌上来，我舍不得走。' },
  { label: '和朋友的旅行', icon: 'people', story: '我们和朋友坐着电车穿过陌生的城市，一路笑着，忘了要在哪一站下车。' },
  { label: '日常生活碎片', icon: 'cup', story: '下班回家，猫趴在窗边，夕阳照在桌上。那一刻，忙了一天的我终于慢了下来。' },
]
const canEditPhotos = computed(() => ready.value && !busy.value && !preparing.value && !checking.value && !needsCheck.value)
const { credits, refreshCredits } = useCredits()
let owner = ''
let draft: PhotoDraft | undefined
let alive = true

function applyInspiration(story: string) {
  const example = t(story)
  const current = note.value.trimEnd()
  const replace = !current || inspiration.some(item => t(item.story) === current)
  const next = replace ? example : `${current}\n${example}`
  if (next.length > storyLimit) {
    error.value = '故事最多可填写 300 字，请缩短后再添加灵感。'
  } else {
    note.value = next
    if (error.value === '故事最多可填写 300 字，请缩短后再添加灵感。') error.value = ''
  }
  storyInput.value?.focus()
}

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
      || typeof saved.note !== 'string' || saved.note.length > storyLimit || !['zh-CN','en','mixed'].includes(saved.language)
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
  <section class="photo-studio" :class="{ 'photo-studio-embedded': props.embedded }" aria-labelledby="photo-studio-title">
    <div v-if="!props.embedded" class="photo-waves" aria-hidden="true"></div>
    <header class="photo-intro">
      <button v-if="props.embedded" type="button" class="detail-back" @click="emit('close')">{{ t('返回对话') }}</button>
      <RouterLink v-else class="detail-back" to="/create">{{ t('← 返回开始创作') }}</RouterLink>
      <div class="photo-title">
        <h1 id="photo-studio-title">{{ t('用照片写一首歌') }}</h1>
        <span class="photo-title-flourish" aria-hidden="true"></span>
      </div>
      <p>{{ t('选几张想写进歌里的照片，也可以告诉我们照片背后的故事，让 AI 帮你把记忆写成一首动人的歌。') }}</p>
    </header>
    <form class="photo-form" :aria-busy="busy || checking || preparing" @submit.prevent="submit">
      <div class="photo-upload-card">
        <div class="photo-frame">
          <div class="photo-stage">
            <ol v-if="photos.length" class="photo-previews">
              <li v-for="(photo, index) in photos" :key="photo.preview">
                <img :src="photo.preview" :alt="t('第 {number} 张照片', { number: index + 1 })">
                <button type="button" class="photo-remove" :disabled="!canEditPhotos"
                  :aria-label="t('删除第 {number} 张照片', { number: index + 1 })" @click="removePhoto(index)">×</button>
              </li>
            </ol>
            <div v-else class="photo-collage" aria-hidden="true">
              <span class="photo-print photo-print-mountains"><span class="photo-image photo-image-mountains"></span></span>
              <span class="photo-print photo-print-city"><span class="photo-image photo-image-city"></span></span>
              <span class="photo-print photo-print-sea"><span class="photo-image photo-image-sea"></span></span>
              <NavIcon class="photo-collage-note" name="music"/>
            </div>
          </div>
          <label class="photo-picker photo-cta">
            <input type="file" accept="image/jpeg,image/png" multiple
              :disabled="!canEditPhotos || photos.length >= maxPhotoCount" @change="choosePhotos">
            <NavIcon name="upload"/>
            <span>{{ t(photos.length ? '添加照片' : '选择照片') }}</span>
          </label>
          <small class="photo-upload-help">{{ t('支持 1–5 张 JPG 或 PNG，单张不超过 10 MB') }}</small>
          <small v-if="preparing || photos.length" class="photo-upload-status" role="status">
            {{ preparing ? t('正在处理照片…') : t('已选 {count}/{max} 张', { count: photos.length, max: maxPhotoCount }) }}
          </small>
          <div v-if="!photos.length" class="photo-example-strip">
            <small>{{ t('示例：可选择 1–5 张照片') }}</small>
            <div class="photo-example-images" aria-hidden="true">
              <span v-for="example in photoExamples" :key="example" class="photo-image" :class="'photo-image-' + example"></span>
            </div>
          </div>
        </div>
      </div>
      <div class="photo-fields">
        <label class="photo-field">
          <span>{{ t('照片背后的故事') }} <small>{{ t('（选填）') }}</small></span>
          <textarea ref="storyInput" v-model="note" rows="4" :maxlength="storyLimit" :disabled="busy || locked"
            :placeholder="t('例如：这是我第一次看海。')"/>
        </label>
        <label class="photo-field">
          <span>{{ t('歌词语言') }}</span>
          <select v-model="language" :disabled="busy || locked">
            <option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option>
            <option value="mixed">{{ t('中英混合') }}</option>
          </select>
        </label>
        <div class="photo-inspiration">
          <div class="photo-inspiration-heading">
            <NavIcon name="create"/>
            <strong>{{ t('灵感示例') }}</strong>
            <small>{{ t('不知道怎么写？试试这些灵感描述：') }}</small>
          </div>
          <div class="photo-inspiration-options">
            <button v-for="example in inspiration" :key="example.label" type="button" :disabled="busy || locked"
              @click="applyInspiration(example.story)">
              <NavIcon :name="example.icon"/>
              {{ t(example.label) }}
            </button>
          </div>
        </div>
        <p v-if="error" class="notice error" role="alert">{{ t(error) }}
          <button v-if="creditShort && props.embedded" type="button" @click="emit('recharge')">{{ t('去充值') }}</button>
          <RouterLink v-else-if="creditShort" to="/recharge">{{ t('去充值') }}</RouterLink>
        </p>
        <div class="photo-actions">
          <button v-if="needsCheck" class="photo-cta photo-submit" type="button" :disabled="checking || busy" @click="checkOriginal">
            <NavIcon name="recovery"/>
            {{ t(checking ? '正在核对歌词进度…' : '核对歌词进度') }}
          </button>
          <button v-else class="photo-cta photo-submit" type="submit" :disabled="!canEditPhotos || !photos.length">
            <NavIcon name="music"/>
            {{ t(busy ? '正在准备照片并编写歌词…' : '生成歌词') }}
          </button>
          <small v-if="props.embedded && credits && credits.lyricsCost > 0 && !needsCheck">{{ t('本次生成消耗 {cost} 积分', { cost: credits.lyricsCost }) }}</small>
          <CreditCostHint v-else-if="credits && credits.lyricsCost > 0 && !needsCheck" :cost="credits.lyricsCost" :balance="credits.balance" :busy="busy"/>
          <small class="photo-privacy">{{ t('照片会发送给 AI 用于本次创作。分享作品时不会公开原图。') }}</small>
        </div>
        <button v-if="locked && !busy && !needsCheck" type="button" class="text-button" @click="reset">{{ t('修改创作素材') }}</button>
        <button v-if="outcomeUnknown && !checking" type="button" class="text-button" @click="reset">{{ t('开始新的照片创作') }}</button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.photo-studio {
  --photo-ink:#30282e;
  --photo-muted:#776871;
  --photo-line:#eddee3;
  --photo-accent:#ad332c;
  --photo-cta:linear-gradient(104deg,#ee523d,#ea283f);
  position:relative;
  isolation:isolate;
  padding:34px 24px 58px;
  color:var(--photo-ink);
  background:radial-gradient(ellipse at 48% 43%,#fffdfd 0%,#fdf5f8 61%,#fdf0f5 100%);
}
.photo-intro,.photo-form { max-width:1200px; margin-inline:auto; }
.photo-intro { padding-inline:4px; margin-bottom:24px; }
.photo-intro .detail-back { min-height:38px; margin:0 0 12px; color:var(--photo-muted); font-weight:650; }
.photo-intro .detail-back:hover { color:var(--photo-accent); }
.photo-title { display:flex; align-items:center; gap:16px; }
.photo-title h1 { margin:0; color:var(--photo-ink); font-size:clamp(30px,4.1vw,48px); line-height:1.2; font-weight:800; letter-spacing:-.035em; }
.photo-title-flourish { position:relative; flex:0 0 116px; height:29px; transform:translateY(7px); }
.photo-title-flourish::before,.photo-title-flourish::after { content:''; position:absolute; border:2px solid #ffa3a5; }
.photo-title-flourish::before { width:45px; height:14px; top:8px; left:0; border-color:#ffa3a5 #ffa3a5 transparent transparent; border-radius:50%; transform:rotate(-24deg); }
.photo-title-flourish::after { width:84px; height:13px; top:13px; left:37px; border-color:#ffb0b0 transparent transparent; border-radius:50%; transform:rotate(-13deg); }
.photo-intro p { margin:9px 0 0; color:#655962; font-size:clamp(14px,1.4vw,17px); line-height:1.65; }

.photo-waves { position:absolute; inset:0; z-index:-1; overflow:hidden; pointer-events:none; }
.photo-waves::before,.photo-waves::after {
  content:'';
  position:absolute;
  width:420px;
  height:146px;
  background:repeating-radial-gradient(ellipse at 50% 115%,transparent 0 5px,#fa949e42 5.5px 6.5px);
  mask-image:linear-gradient(90deg,transparent,#000 30%,#000 70%,transparent),linear-gradient(0deg,#000 14%,transparent 90%);
  mask-composite:intersect;
  border-radius:50%;
}
.photo-waves::before { top:196px; left:-188px; transform:rotate(-20deg); }
.photo-waves::after { top:22px; right:-125px; transform:rotate(-12deg); }

.photo-form { display:grid; grid-template-columns:minmax(0,1.22fr) minmax(0,1fr); gap:20px; }
.photo-upload-card,.photo-fields {
  min-width:0;
  border:1px solid var(--photo-line);
  border-radius:24px;
  background:#fffdfde8;
  box-shadow:0 8px 24px #9e59640a;
}
.photo-upload-card { display:flex; padding:23px; }
.photo-frame {
  display:flex;
  flex:1;
  min-width:0;
  flex-direction:column;
  align-items:center;
  justify-content:center;
  padding:22px 25px 28px;
  border:1px dashed #ed9b9d;
  border-radius:19px;
  background:radial-gradient(ellipse at 50% 36%,#ffe9e7a8,transparent 61%),linear-gradient(145deg,#fdf0f0,#fdf4f7);
}
.photo-stage { display:grid; width:100%; min-width:0; min-height:245px; place-items:center; }
.photo-collage {
  position:relative;
  isolation:isolate;
  width:min(100%,405px);
  aspect-ratio:1.73;
  background:radial-gradient(ellipse at 50% 49%,#ffb8bb5c,transparent 68%);
}
.photo-collage::before,.photo-collage::after {
  content:'';
  position:absolute;
  z-index:-1;
  border:2px solid #fff9;
  border-radius:50%;
  transform:rotate(5deg);
}
.photo-collage::before { inset:54% 2% 10%; }
.photo-collage::after { inset:48% 11% 22%; }
.photo-image {
  display:block;
  background-image:url('../../../assets/studio/photo-inspiration.webp');
  background-repeat:no-repeat;
  background-size:300% 200%;
}
.photo-image-sea { background-position:0 0; }
.photo-image-mountains { background-position:50% 0; }
.photo-image-tram { background-position:100% 0; }
.photo-image-blossoms { background-position:0 100%; }
.photo-image-cat { background-position:50% 100%; }
.photo-image-city { background-position:100% 100%; }
.photo-print {
  position:absolute;
  width:29%;
  padding:1.7% 1.7% 5%;
  border-radius:8%;
  background:#fffaf9;
  box-shadow:0 12px 20px #985e6927;
}
.photo-print .photo-image { aspect-ratio:1; border-radius:5%; }
.photo-print-mountains { top:31%; left:14%; transform:rotate(-12deg); }
.photo-print-city { top:34%; right:14%; transform:rotate(12deg); }
.photo-print-sea { top:14%; left:32%; width:38%; transform:rotate(-6deg); }
.photo-collage-note { position:absolute; top:10%; right:10%; width:31px; height:31px; color:#ef858e; transform:rotate(15deg); }

.photo-previews { display:flex; flex-wrap:wrap; justify-content:center; gap:10px; width:100%; margin:0; padding:0; list-style:none; }
.photo-previews li { position:relative; flex:0 1 28%; min-width:0; max-width:150px; aspect-ratio:1; }
.photo-previews img { display:block; width:100%; height:100%; object-fit:cover; border:3px solid #fffdfd; border-radius:10px; }
.photo-remove { position:absolute; top:4px; right:4px; min-width:36px; min-height:36px; padding:0; border:1px solid var(--photo-line); border-radius:50%; background:#fffdfd; font-size:22px; line-height:1; }
.photo-remove:hover:not(:disabled) { color:var(--photo-accent); }
.photo-cta {
  display:inline-flex;
  align-items:center;
  justify-content:center;
  gap:10px;
  border:1px solid #fff4;
  border-radius:999px;
  background:var(--photo-cta);
  color:#fff;
  font-weight:750;
  box-shadow:0 5px 13px #e6474f24;
}
.photo-cta svg { flex:none; width:22px; height:22px; }
.photo-picker { position:relative; width:min(100%,210px); min-height:46px; margin-top:18px; padding:8px 16px; font-size:16px; }
.photo-picker input { position:absolute; inset:0; width:100%; height:100%; opacity:0; cursor:pointer; }
.photo-picker input:disabled { cursor:not-allowed; }
.photo-picker:focus-within { outline:2px solid var(--photo-accent); outline-offset:3px; }
.photo-picker:has(input:disabled),.photo-submit:disabled { filter:saturate(.65); opacity:.78; box-shadow:none; }
.photo-picker:has(input:not(:disabled)):hover,.photo-submit:not(:disabled):hover { background:linear-gradient(104deg,#d74132,#d41d35); }
.photo-upload-help,.photo-upload-status { margin-top:9px; color:var(--photo-muted); font-size:13px; text-align:center; }
.photo-example-strip {
  display:grid;
  grid-template-columns:auto minmax(0,368px);
  align-items:center;
  justify-content:space-between;
  gap:14px;
  width:100%;
  margin-top:23px;
  padding-top:18px;
  border-top:1px solid var(--photo-line);
}
.photo-example-strip small { color:var(--photo-muted); font-size:13px; }
.photo-example-images { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:10px; }
.photo-example-images .photo-image { aspect-ratio:1; border-radius:11px; }

.photo-fields { display:flex; flex-direction:column; gap:16px; padding:27px clamp(22px,2.1vw,35px) 26px; }
.photo-field { display:grid; gap:8px; color:var(--photo-ink); font-size:17px; font-weight:750; }
.photo-field small { color:#5e525b; font-size:.86em; font-weight:450; }
.photo-field textarea,.photo-field select {
  width:100%;
  border:1px solid #e7d8de;
  border-radius:14px;
  background:#fffafd;
  color:var(--photo-ink);
  font-size:16px;
  font-weight:400;
}
.photo-field textarea { min-height:115px; padding:10px 14px; line-height:1.6; resize:vertical; }
.photo-field textarea::placeholder { color:#796b75; }
.photo-field select { min-height:44px; padding:7px 12px; }
.photo-field textarea:focus,.photo-field select:focus { border-color:#d48085; }
.photo-inspiration { margin-top:16px; }
.photo-inspiration-heading { display:flex; flex-wrap:wrap; align-items:center; gap:7px 10px; line-height:1.45; }
.photo-inspiration-heading svg { flex:none; width:20px; height:20px; color:#e74b44; }
.photo-inspiration-heading strong { font-size:16px; }
.photo-inspiration-heading small { color:var(--photo-muted); font-size:13px; }
.photo-inspiration-options { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
.photo-inspiration-options button {
  display:inline-flex;
  align-items:center;
  gap:7px;
  min-height:39px;
  padding:6px 11px;
  border:1px solid #f2d8dc;
  border-radius:999px;
  background:#fff2f2;
  color:var(--photo-accent);
  font-size:14px;
  font-weight:650;
}
.photo-inspiration-options button:hover:not(:disabled) { background:#ffe5e6; border-color:#e4a2a7; }
.photo-inspiration-options button:disabled { opacity:.65; }
.photo-inspiration-options svg { flex:none; width:17px; height:17px; }
.photo-fields .notice { margin:0; }
.photo-actions { display:flex; flex-direction:column; align-items:center; gap:8px; margin-top:auto; padding-top:17px; }
.photo-submit { width:100%; min-height:52px; padding:10px 18px; font-size:20px; line-height:1.3; }
.photo-actions small { color:var(--photo-muted); font-size:13px; text-align:center; }
.photo-studio-embedded { padding:16px 0 8px; background:none; }
.photo-studio-embedded .photo-title h1 { font-size:clamp(28px,3vw,38px); }
.photo-studio-embedded .photo-stage { min-height:184px; }

@media(max-width:1100px) {
  .photo-upload-card { padding:16px; }
  .photo-frame { padding-inline:16px; }
  .photo-example-strip { grid-template-columns:1fr; gap:8px; }
  .photo-example-images { width:min(100%,368px); }
}
@media(max-width:760px) {
  .photo-studio { padding:21px 15px 38px; }
  .photo-studio-embedded { padding-inline:0; }
  .photo-form { grid-template-columns:1fr; gap:16px; }
  .photo-intro { margin-bottom:18px; }
  .photo-title { gap:8px; }
  .photo-title-flourish { flex-basis:88px; transform:translateY(7px) scale(.72); transform-origin:left center; }
  .photo-upload-card,.photo-fields { border-radius:19px; }
  .photo-upload-card { padding:12px; }
  .photo-frame { padding:12px 15px 18px; border-radius:14px; }
  .photo-stage { min-height:165px; }
  .photo-collage { max-width:326px; }
  .photo-picker { margin-top:10px; }
  .photo-example-strip { margin-top:16px; padding-top:12px; }
  .photo-example-images { gap:7px; }
  .photo-fields { padding:18px 16px; }
  .photo-inspiration { margin-top:5px; }
  .photo-inspiration-options button { min-height:44px; }
  .photo-actions { padding-top:5px; }
}
@media(max-width:380px) {
  .photo-title-flourish { display:none; }
  .photo-inspiration-heading small { flex-basis:100%; }
}
</style>
