<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { t, defaultCover } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { audioUrl, type AudioAsset } from '../api/detail'

const props = defineProps<{ open: boolean; workId?: string; asset?: AudioAsset; title?: string; cover?: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>(), content = ref<HTMLElement>()
const selected = ref(''), notice = ref(''), copied = ref(false)
const player = usePlayer()
const plans = [
  { id: 'professional', title: '专业制作', price: '¥20,000', suffix: '起', description: '让你的歌曲拥有完整的专业制作', features: ['专业编曲', '录音指导', '混音与母带', '音乐平台发行'] },
  { id: 'producer', title: '高级制作人定制', price: '¥50,000', suffix: '起', description: '与资深制作人一起打磨你的声音', features: ['资深制作人参与', '专属创作与编曲', '完整音乐制作'] },
  { id: 'bespoke', title: '顶级定制', price: '¥100,000+', suffix: '', description: '围绕你的音乐目标定制完整方案', features: ['制作人及团队定制', '录音棚与制作资源', '发行需求单独沟通', '全球音乐发行', '真人视频拍摄'] },
]
const chosen = computed(() => plans.find(plan => plan.id === selected.value))
const images = [
  { title: '大师编曲', src: '/media/premium-studio/master-arrangement.jpg', text: '从旋律到乐器层次 让音乐更有生命力' },
  { title: '大师混音', src: '/media/premium-studio/master-mixing.png', text: '打磨人声与声音细节 让每一层表达都被听见' },
]
const representativeWorks = [
  { title: '《一笑江湖》', credits: '闻人听書_ · 蒋雪儿制作 · 祝何词曲与编曲', note: '“科目三”配乐' },
  { title: '《小城夏天》', credits: 'LBI 利比 · 青风音乐发行' },
  { title: '《踏山河》', credits: '七叔（叶泽浩） · 田跃君制作 · 祝何词曲与编曲' },
  { title: '《半生雪》', credits: '七叔（叶泽浩） · DJ Yaha 版由蒋雪儿、祝何制作' },
  { title: '《相思遥》', credits: '玉慧同学 · 蒋雪儿制作' },
  { title: '《莫问归期》', credits: '蒋雪儿 · 青风音乐发行（2024 版）' },
]
const preview = ref<(typeof images)[number]>()
const previewBack = ref<HTMLButtonElement>()
const gallery = ref<HTMLElement>()
let galleryScroll = 0
const playing = computed(() => !!props.asset && player.current?.id === props.asset.id && (player.playing || player.starting))
const duration = computed(() => {
  if (!props.asset || player.current?.id !== props.asset.id || !player.duration) return ''
  return `${Math.floor(player.duration / 60).toString().padStart(2, '0')}:${Math.floor(player.duration % 60).toString().padStart(2, '0')}`
})
function play() {
  if (!props.asset || !props.workId) return
  void player.toggle({ id: props.asset.id, title: props.title || t('专属音乐制作'), cover: props.cover || defaultCover(), audio: audioUrl(props.workId, props.asset.id), provenance: props.asset.origin })
}
async function showImage(item: (typeof images)[number]) {
  galleryScroll = content.value?.scrollTop ?? 0
  preview.value = item
  await nextTick()
  if (content.value) content.value.scrollTop = 0
  previewBack.value?.focus({ preventScroll: true })
}
async function closeImage() {
  const image = preview.value
  preview.value = undefined
  await nextTick()
  if (content.value) content.value.scrollTop = galleryScroll
  if (image) gallery.value?.querySelectorAll('button')[images.findIndex(item => item.src === image.src)]?.focus({ preventScroll: true })
}
function cancel() {
  if (preview.value) void closeImage()
  else emit('close')
}
function choose(id: string) { selected.value = id; notice.value = ''; copied.value = false }
const consultationEmail = 'labelmanager@cheerfulmusic.co.uk'
const brief = computed(() => {
  const plan = chosen.value
  if (!plan) return ''
  return [t('专属音乐制作咨询'), ...(props.title ? [`${t('歌曲名称')} ${props.title}`] : []), `${t('制作方案')} ${t(plan.title)}`, `${t('参考预算')} ${plan.price}${t(plan.suffix)}`, `${t('制作内容')} ${plan.features.map(feature => t(feature)).join(' / ')}`, '', t('希望进一步沟通演唱录音 编曲 混音及制作安排')].join('\n')
})
const mailto = computed(() => `mailto:${consultationEmail}?subject=${encodeURIComponent(t('专属音乐制作咨询') + (props.title ? ' - ' + props.title : ''))}&body=${encodeURIComponent(brief.value)}`)
async function copyBrief() {
  if (!chosen.value) return
  try { await navigator.clipboard.writeText(brief.value); copied.value = true; notice.value = '定制需求已复制 可发送邮件与制作团队沟通' }
  catch { copied.value = false; notice.value = '暂时无法复制 请保留当前选择后重试' }
}
watch(() => props.open, async value => {
  await nextTick()
  if (value) { preview.value = undefined; notice.value = ''; if (!dialog.value?.open) dialog.value?.showModal(); if (content.value) content.value.scrollTop = 0 }
  else dialog.value?.close()
}, { immediate: true })
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog ref="dialog" class="premium-studio-dialog" aria-labelledby="premium-studio-title" @cancel.prevent="cancel" @close="open && emit('close')">
    <button class="premium-close" type="button" :aria-label="t('关闭定制音乐制作')" @click="emit('close')">×</button>
    <div ref="content" class="premium-scroll">
      <template v-if="preview">
        <button ref="previewBack" class="premium-back" type="button" @click="closeImage">← {{ t('返回专属音乐制作') }}</button>
        <h2 id="premium-studio-title">{{ t(preview.title) }}</h2>
        <img class="premium-full-image" :src="preview.src" :alt="t(preview.title)">
        <p class="premium-image-note">{{ t('制作场景展示') }}</p>
      </template>
      <template v-else>
        <p class="premium-kicker">Premium Music Experience</p>
        <h2 id="premium-studio-title">{{ t('专属音乐制作') }}</h2>
        <p class="premium-description">{{ t('让青风音乐制作团队为你的歌完成编曲、录音与混音。') }}</p>
        <div v-if="asset && workId" class="premium-song">
          <img :src="cover || defaultCover()" :alt="t('歌曲封面')">
          <div><h3>{{ title }}</h3><p>{{ t('当前试听版本') }}<span v-if="duration"> · {{ duration }}</span></p></div>
          <button class="premium-play" type="button" @click="play"><PlaybackIcon :playing="playing"/><span>{{ playing ? t('暂停') : t('试听歌曲') }}</span></button>
        </div>
        <section class="premium-about" aria-labelledby="premium-about-title">
          <header class="premium-about-header">
            <h3 id="premium-about-title">{{ t('关于青风音乐') }}</h3>
            <a class="premium-profile-download" href="/media/premium-studio/cheerful-music-company-profile-zh-260730.pdf"
               download="青风音乐 Cheerful Music260730中文版.pdf" :aria-label="t('下载青风音乐公司介绍 PDF（中文版）')">
              <span aria-hidden="true">↓</span>{{ t('下载 PDF') }}
            </a>
          </header>
          <p>{{ t('青风音乐（Cheerful Music）由歌手兼制作人蒋雪儿（Snow.J）创办，2019 年在深圳成立，2023 年设立伦敦办公室。团队深耕国风与华语流行，参与制作或发行的《踏山河》《小城夏天》《半生雪》均登顶过国内主流音乐平台榜单。') }}</p>
          <p>{{ t('《一笑江湖》随“科目三”舞蹈走红海外，2025 年登上 Spotify 日本 Viral 50 榜第 2 名。《相思遥》入选全球 600 多份歌单，张含韵和郭雨昂在 2025 年除夕的央视综合频道节目《金蛇起舞中国年味》中演唱了这首歌。') }}</p>
          <p>{{ t('青风音乐的制作团队覆盖词曲创作、编曲、录音与混音。国际合作作品包括《半生雪》的 Madism 混音版，以及七叔与瑞典音乐人 Kezano 合作的双语单曲《By Your Side》。自 2025 年起，青风音乐举办国际音乐写作营，邀请中国、美国、丹麦和澳大利亚的音乐人共同创作。') }}</p>
          <p>{{ t('青风音乐与索尼音乐达成新歌共创和音乐发行合作，携手 Believe 开展全球数字发行，并由环球音乐版权独家代理其在中国大陆以外的词曲版权。在专属音乐制作中，团队会围绕你想保留的旋律和情绪，从编曲、录音到混音打磨你的歌曲。') }}</p>
          <p class="premium-reach">{{ t('《一笑江湖》相关抖音视频观看量超 500 亿次') }} <a href="https://www.umusicpub.com/us/news/umpg-china-cheerful-music" target="_blank" rel="noopener noreferrer">{{ t('环球音乐版权 2024 年公告') }}</a></p>
        </section>
        <section class="premium-works" aria-labelledby="premium-works-title">
          <h3 id="premium-works-title">{{ t('代表作品与制作署名') }}</h3>
          <ul class="premium-track-list">
            <li v-for="work in representativeWorks" :key="work.title">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" aria-hidden="true"><path d="M9 17V6l11-2v11M9 17a3.3 2.2 0 1 1-6 0 3.3 2.2 0 1 1 6 0ZM20 15a3.3 2.2 0 1 1-6 0 3.3 2.2 0 1 1 6 0Z"/></svg>
              <div><strong>{{ t(work.title) }}</strong><small>{{ t(work.credits) }}</small></div>
              <span v-if="work.note" class="premium-track-note">{{ t(work.note) }}</span>
            </li>
          </ul>
        </section>
        <div class="premium-gallery-heading"><h3>{{ t('制作展示') }}</h3><span>{{ t('点击图片查看大图') }}</span></div>
        <div ref="gallery" class="premium-gallery">
          <button v-for="item in images" :key="item.title" type="button" @click="showImage(item)">
            <img :src="item.src" :alt="t(item.title)" loading="lazy"><span><strong>{{ t(item.title) }}</strong><small>{{ t(item.text) }}</small></span><i aria-hidden="true">↗</i>
          </button>
        </div>
        <fieldset class="premium-plans"><legend>{{ t('选择制作方案') }}</legend>
          <label v-for="plan in plans" :key="plan.id" class="premium-plan" :class="{ selected: selected === plan.id }">
            <input type="radio" name="premium-plan" :value="plan.id" :checked="selected === plan.id" @change="choose(plan.id)">
            <span class="premium-plan-name">{{ t(plan.title) }}</span><span class="premium-price">{{ plan.price }}<small v-if="plan.suffix">{{ t(plan.suffix) }}</small></span>
            <span class="premium-plan-description">{{ t(plan.description) }}</span>
            <span class="premium-features"><span v-for="feature in plan.features" :key="feature">{{ t(feature) }}</span></span>
          </label>
        </fieldset>
        <p class="premium-price-note">{{ t('价格为人民币参考预算 最终按制作人 录音棚 制作团队及发行需求单独报价 选择方案不会扣款') }}</p>
        <div class="premium-contact"><span>{{ t('制作咨询') }} <a :href="'mailto:' + consultationEmail">{{ consultationEmail }}</a></span><button type="button" :disabled="!chosen" @click="copyBrief">{{ copied ? t('再次复制需求') : t('复制定制需求') }}</button><small>{{ t('预约按钮将打开邮件草稿 由你确认发送') }}</small></div>
        <div v-if="notice" class="premium-notice" role="status">{{ t(notice) }}</div>
      </template>
    </div>
    <footer v-if="!preview" class="premium-summary">
      <div><span>{{ chosen ? t('已选制作方案') : t('请选择一个制作方案') }}</span><strong v-if="chosen">{{ t(chosen.title) }} <span>{{ chosen.price }}{{ t(chosen.suffix) }}</span></strong><small v-else>{{ t('先了解方案 再决定如何制作') }}</small></div>
      <a v-if="chosen" class="premium-primary" :href="mailto">{{ t('邮件预约咨询') }} <span aria-hidden="true">↗</span></a><button v-else class="premium-primary" type="button" disabled>{{ t('邮件预约咨询') }} <span aria-hidden="true">↗</span></button>
    </footer>
  </dialog>
</template>

<style scoped>
.premium-studio-dialog{width:min(960px,calc(100% - 32px));max-width:none;max-height:calc(100dvh - 40px);padding:0;border:1px solid #3b3439;border-radius:28px;background:#121115;color:#faf8f6;box-shadow:0 30px 100px #21111a55;color-scheme:dark;overflow:hidden}
.premium-studio-dialog[open]{display:flex;flex-direction:column}
.premium-studio-dialog::backdrop{background:#24191f88;backdrop-filter:blur(8px)}
.premium-close{position:absolute;right:16px;top:12px;z-index:2;width:42px;height:42px;border:0;border-radius:50%;background:#302a31;color:#fff;font-size:28px}
.premium-scroll{min-height:0;flex:1 1 auto;padding:34px;overflow:auto}

.premium-studio-dialog h2{font-family:inherit;font-size:32px;font-weight:700;line-height:1.3;color:#fff;margin:8px 48px 12px 0;letter-spacing:-.6px}
.premium-song { display:flex; align-items:center; gap:12px; padding:8px 11px; margin:0 0 23px; border:1px solid #383039; border-radius:13px; background:#1f1a21; }
.premium-song > img { width:40px; height:40px; border-radius:8px; object-fit:cover; flex:none; }
.premium-song > div { min-width:0; }
.premium-song h3 { font-size:14px; line-height:1.4; color:#fff; margin:0; overflow-wrap:anywhere; }
.premium-song p { font-size:12px; color:#b9aab6; margin:3px 0 0; }
.premium-play { display:inline-flex; align-items:center; gap:6px; min-height:44px; margin-left:auto; background:transparent; border:0; color:#ff8f7e; padding:0 5px; font-size:13px; text-align:left; }
.premium-play .playback-icon { width:17px; height:17px; flex:none; }
.premium-kicker { font-size:13px; color:#ffa898; margin:0 0 7px; }
.premium-about { border:1px solid #3b3039; background:#221b22; border-radius:17px; padding:19px 20px; margin-bottom:22px; }
.premium-about h3,.premium-works h3 { font-size:19px; font-weight:650; color:#fff; margin:0 0 8px; }
.premium-about-header { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:6px 12px; margin-bottom:10px; }
.premium-about-header h3 { margin:0; }
.premium-profile-download { display:inline-flex; align-items:center; gap:6px; min-height:36px; padding:0 12px; margin-inline-start:auto; border:1px solid #73514d; border-radius:18px; color:#ffb2a2; text-decoration:none; font-size:13px; font-weight:600; white-space:nowrap; }
.premium-profile-download:hover { background:#382725; border-color:#aa746a; }
.premium-profile-download:focus-visible { outline:2px solid #ffb3a6; outline-offset:4px; }
.premium-about p { font-size:14px; line-height:1.75; color:#c7bac5; margin:0; }
.premium-about p + p { margin-top:8px; }
.premium-about .premium-reach { color:#ffb2a2; margin-top:8px; }
.premium-reach a { color:#c6b7c3; font-size:12px; margin-inline-start:6px; text-underline-offset:3px; }
.premium-works { margin-bottom:24px; }
.premium-track-list { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); column-gap:22px; border-top:1px solid #383039; padding:0; margin:0; list-style:none; }
.premium-track-list li { display:flex; align-items:center; gap:10px; min-height:53px; padding:7px 3px; color:#eee5eb; border-bottom:1px solid #383039; }
.premium-track-list svg { width:18px; height:18px; color:#ffac9b; flex:none; }
.premium-track-list strong { display:block; font-size:14px; line-height:1.45; }
.premium-track-list small { display:block; font-size:12px; line-height:1.55; color:#c6b7c3; }
.premium-track-note { font-size:11px; color:#ffb3a3; background:#382725; border-radius:15px; padding:4px 8px; margin-inline-start:auto; flex:none; }
.premium-primary{text-decoration:none;display:flex;align-items:center;justify-content:center;gap:24px;min-height:52px;border:0;border-radius:30px;background:#f93822;color:#fff;font-size:15px;font-weight:650;padding:13px 24px;cursor:pointer}.premium-primary:disabled{background:#51454d;color:#c1b1bb;cursor:default}.premium-primary:hover:not(:disabled){background:#de2b19}.premium-primary:focus-visible,.premium-gallery button:focus-visible,.premium-close:focus-visible,.premium-back:focus-visible,.premium-play:focus-visible,.premium-reach a:focus-visible{outline:2px solid #ffb3a6;outline-offset:4px}
.premium-back{border:0;background:transparent;color:#c2b2bf;min-height:36px;padding:0;font-size:13px;margin:0 0 18px}.premium-description{font-size:14px;line-height:1.7;color:#b3a8b3;margin:0 0 15px}
.premium-plans{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;border:0;padding:0;margin:0}.premium-plans legend{font-size:19px;font-weight:650;color:#fff;padding:0 0 12px}
.premium-plan{position:relative;display:flex;flex-direction:column;align-items:flex-start;gap:16px;padding:22px 18px;border:1px solid #50454e;border-radius:18px;background:#211c23;cursor:pointer}.premium-plan.selected{background:#382322;border-color:#ff7968;box-shadow:inset 0 0 0 1px #ff7968}.premium-plan:focus-within{outline:2px solid #ffb3a6;outline-offset:3px}.premium-plan input{position:absolute;top:22px;right:16px;width:16px;height:16px;margin:0;accent-color:#f93822}.premium-plan-name{font-size:15px;padding-right:20px;color:#f1e9ee}.premium-price{display:block;font-size:29px;line-height:1.2;font-weight:700;letter-spacing:-1px;color:#fff;white-space:nowrap}.premium-price small{font-size:14px;margin-left:4px;font-weight:500}.premium-plan-description{font-size:13px;line-height:1.8;color:#c0b4bf}.premium-features{display:flex;flex-direction:column;gap:10px;margin-top:auto;font-size:12px;color:#cdbfca}.premium-features>span::before{content:'✓';margin-right:8px;color:#ff9f8e}
.premium-price-note{font-size:12px;line-height:1.8;color:#b5a3b0;margin:16px 0 28px}.premium-gallery-heading{display:flex;justify-content:space-between;gap:16px;align-items:baseline}.premium-gallery-heading h3{font-size:19px;font-weight:600;color:#fff;margin:0 0 16px}.premium-gallery-heading>span{font-size:11px;color:#a99aa6}.premium-gallery{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:24px}.premium-gallery button{position:relative;text-align:left;overflow:hidden;border:1px solid #41343e;border-radius:18px;background:#211b22;color:#fff;padding:0}.premium-gallery img{display:block;width:100%;aspect-ratio:2.5;object-fit:cover}.premium-gallery button>span{display:block;padding:18px 40px 18px 18px}.premium-gallery strong{font-size:18px;font-weight:600;display:block;margin-bottom:8px}.premium-gallery small{font-size:12px;line-height:1.7;color:#b9aab6;display:block}.premium-gallery i{position:absolute;bottom:22px;right:18px;font-style:normal;color:#ff9f8e}
.premium-contact{display:flex;align-items:center;flex-wrap:wrap;gap:12px 20px;margin-top:24px;font-size:13px;color:#c8b6c3}.premium-contact a{color:#ffb4a4;overflow-wrap:anywhere}.premium-contact button{background:transparent;color:#ffd0c4;border:1px solid #71544f;border-radius:22px;padding:8px 14px}.premium-contact button:disabled{opacity:.45}.premium-contact small{display:block;width:100%;font-size:12px;color:#ac9ba8}
.premium-summary{flex-shrink:0;position:sticky;bottom:0;display:flex;justify-content:space-between;align-items:center;gap:20px;margin:0;padding:20px 34px;background:#211c23;border-top:1px solid #493a44;max-width:none;width:100%}.premium-summary>div{min-width:0}.premium-summary>div>span{display:block;font-size:12px;color:#bcaab8;margin-bottom:6px}.premium-summary strong{font-size:15px;color:#fff;line-height:1.6}.premium-summary strong span{color:#ffad9d;white-space:nowrap}.premium-summary small{font-size:12px;color:#d5c4d0}.premium-summary .premium-primary{text-decoration:none;flex-shrink:0}.premium-notice{padding:16px 0 0;font-size:13px;line-height:1.8;color:#ffb7a8}.premium-full-image{display:block;max-width:100%;max-height:65dvh;margin:20px auto;object-fit:contain;border-radius:12px}.premium-image-note{text-align:center;color:#bdaebb;font-size:12px}
@media (max-width: 640px) {
  .premium-studio-dialog { width:calc(100% - 20px); max-height:calc(100dvh - 20px); border-radius:22px; }
  .premium-scroll { padding:26px 20px; }
  .premium-studio-dialog h2 { font-size:25px; margin-right:34px; }
  .premium-about { padding:16px; }
  .premium-about-header { padding-inline-end:24px; }
  .premium-track-list,.premium-plans,.premium-gallery { grid-template-columns:1fr; }
  .premium-plan { gap:10px; padding:18px; }
  .premium-price { font-size:30px; }
  .premium-features { flex-direction:row; flex-wrap:wrap; gap:8px 14px; }
  .premium-gallery-heading { gap:8px; }
  .premium-summary { padding:16px 20px; gap:12px; align-items:stretch; flex-direction:column; }
  .premium-summary > div > span { display:none; }
  .premium-summary strong { font-size:14px; }
  .premium-summary .premium-primary { min-height:46px; width:100%; }
  .premium-price-note { margin-bottom:24px; }
}
</style>
