<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { t, defaultCover } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { audioUrl, type AudioAsset } from '../api/detail'

const props = defineProps<{ open: boolean; workId: string; asset: AudioAsset; title: string; cover?: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>(), content = ref<HTMLElement>()
const stage = ref<'intro' | 'plans'>('intro'), selected = ref(''), notice = ref(''), copied = ref(false)
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
const preview = ref<(typeof images)[number]>()
const playing = computed(() => player.current?.id === props.asset.id && (player.playing || player.starting))
const duration = computed(() => {
  if (player.current?.id !== props.asset.id || !player.duration) return ''
  return `${Math.floor(player.duration / 60).toString().padStart(2, '0')}:${Math.floor(player.duration % 60).toString().padStart(2, '0')}`
})
function play() {
  void player.toggle({ id: props.asset.id, title: props.title, cover: props.cover || defaultCover(), audio: audioUrl(props.workId, props.asset.id), provenance: props.asset.origin })
}
async function showPlans() { stage.value = 'plans'; await nextTick(); if (content.value) content.value.scrollTop = 0 }
function choose(id: string) { selected.value = id; notice.value = ''; copied.value = false }
const consultationEmail = 'labelmanager@cheerfulmusic.co.uk'
const brief = computed(() => {
  const plan = chosen.value
  if (!plan) return ''
  return [t('专属音乐制作咨询'), `${t('歌曲名称')} ${props.title}`, `${t('制作方案')} ${t(plan.title)}`, `${t('参考预算')} ${plan.price}${t(plan.suffix)}`, `${t('制作内容')} ${plan.features.map(feature => t(feature)).join(' / ')}`, '', t('希望进一步沟通演唱录音 编曲 混音及制作安排')].join('\n')
})
const mailto = computed(() => `mailto:${consultationEmail}?subject=${encodeURIComponent(t('专属音乐制作咨询') + ' - ' + props.title)}&body=${encodeURIComponent(brief.value)}`)
async function copyBrief() {
  if (!chosen.value) return
  try { await navigator.clipboard.writeText(brief.value); copied.value = true; notice.value = '定制需求已复制 可发送邮件与制作团队沟通' }
  catch { copied.value = false; notice.value = '暂时无法复制 请保留当前选择后重试' }
}
watch(() => props.open, async value => {
  await nextTick()
  if (value) { stage.value = 'intro'; preview.value = undefined; notice.value = ''; if (!dialog.value?.open) dialog.value?.showModal(); if (content.value) content.value.scrollTop = 0 }
  else dialog.value?.close()
}, { immediate: true })
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog ref="dialog" class="premium-studio-dialog" :class="{ 'premium-expanded': stage === 'plans' || preview }" aria-labelledby="premium-studio-title" @cancel.prevent="preview ? preview = undefined : emit('close')" @close="open && emit('close')">
    <button class="premium-close" type="button" :aria-label="t('关闭定制音乐制作')" @click="emit('close')">×</button>
    <div ref="content" class="premium-scroll">
      <template v-if="preview">
        <button class="premium-back" type="button" @click="preview = undefined">← {{ t('返回制作方案') }}</button>
        <h2 id="premium-studio-title">{{ t(preview.title) }}</h2>
        <img class="premium-full-image" :src="preview.src" :alt="t(preview.title)">
        <p class="premium-image-note">{{ t('制作场景展示') }}</p>
      </template>
      <template v-else-if="stage === 'intro'">
        <h2 id="premium-studio-title">{{ t('你的歌诞生了') }}</h2>
        <div class="premium-song">
          <img :src="cover || defaultCover()" :alt="t('歌曲封面')">
          <div><h3>{{ title }}</h3><p>{{ asset.origin === 'GENERATED' ? t('AI原创歌曲') : t('我的音乐作品') }}<span v-if="duration"> · {{ duration }}</span></p><button class="premium-play" type="button" :aria-label="playing ? t('暂停') : t('试听歌曲')" @click="play"><PlaybackIcon :playing="playing"/><span>{{ playing ? t('暂停') : t('试听歌曲') }}</span></button></div>
        </div>
        <section class="premium-invitation">
          <p class="premium-kicker">Premium Studio</p>
          <h3>{{ t('想亲自演唱这首歌吗') }}</h3>
          <p>{{ t('让专业制作人与你一起完成录音 编曲和混音 将这首歌打磨成属于你的专业作品') }}</p>
          <button class="premium-primary" type="button" @click="showPlans">{{ t('了解专属音乐制作') }} <span aria-hidden="true">↗</span></button>
        </section>
      </template>
      <template v-else>
        <button class="premium-back" type="button" @click="stage = 'intro'">← {{ t('返回歌曲') }}</button>
        <p class="premium-kicker">Premium Music Experience</p>
        <h2 id="premium-studio-title">{{ t('专属音乐制作') }}</h2>
        <p class="premium-description">{{ t('选择适合你的制作预算 让专业团队陪你完成下一步') }}</p>
        <fieldset class="premium-plans"><legend>{{ t('选择制作方案') }}</legend>
          <label v-for="plan in plans" :key="plan.id" class="premium-plan" :class="{ selected: selected === plan.id }">
            <input type="radio" name="premium-plan" :value="plan.id" :checked="selected === plan.id" @change="choose(plan.id)">
            <span class="premium-plan-name">{{ t(plan.title) }}</span><span class="premium-price">{{ plan.price }}<small v-if="plan.suffix">{{ t(plan.suffix) }}</small></span>
            <span class="premium-plan-description">{{ t(plan.description) }}</span>
            <span class="premium-features"><span v-for="feature in plan.features" :key="feature">{{ t(feature) }}</span></span>
          </label>
        </fieldset>
        <p class="premium-price-note">{{ t('价格为人民币参考预算 最终按制作人 录音棚 制作团队及发行需求单独报价 选择方案不会扣款') }}</p>
        <div class="premium-gallery-heading"><h3>{{ t('让专业细节 被听见') }}</h3><span>{{ t('点击图片查看大图') }}</span></div>
        <div class="premium-gallery"><button v-for="item in images" :key="item.title" type="button" @click="preview = item"><img :src="item.src" :alt="t(item.title)" loading="lazy"><span><strong>{{ t(item.title) }}</strong><small>{{ t(item.text) }}</small></span><i aria-hidden="true">↗</i></button></div>
        <div class="premium-contact"><span>{{ t('制作咨询') }} <a :href="'mailto:' + consultationEmail">{{ consultationEmail }}</a></span><button type="button" :disabled="!chosen" @click="copyBrief">{{ copied ? t('再次复制需求') : t('复制定制需求') }}</button><small>{{ t('预约按钮将打开邮件草稿 由你确认发送') }}</small></div>
        <div v-if="notice" class="premium-notice" role="status">{{ t(notice) }}</div>
      </template>
    </div>
    <footer v-if="stage === 'plans' && !preview" class="premium-summary">
      <div><span>{{ chosen ? t('已选制作方案') : t('请选择一个制作方案') }}</span><strong v-if="chosen">{{ t(chosen.title) }} <span>{{ chosen.price }}{{ t(chosen.suffix) }}</span></strong><small v-else>{{ t('先了解方案 再决定如何制作') }}</small></div>
      <a v-if="chosen" class="premium-primary" :href="mailto">{{ t('邮件预约咨询') }} <span aria-hidden="true">↗</span></a><button v-else class="premium-primary" type="button" disabled>{{ t('邮件预约咨询') }} <span aria-hidden="true">↗</span></button>
    </footer>
  </dialog>
</template>

<style scoped>
.premium-studio-dialog{width:min(660px,calc(100% - 32px));max-width:none;max-height:calc(100dvh - 40px);padding:0;border:1px solid #3b3439;border-radius:28px;background:#121115;color:#faf8f6;box-shadow:0 30px 100px #21111a55;color-scheme:dark;overflow:hidden}
.premium-studio-dialog[open]{display:flex;flex-direction:column}
.premium-studio-dialog.premium-expanded{width:min(960px,calc(100% - 32px))}
.premium-studio-dialog::backdrop{background:#24191f88;backdrop-filter:blur(8px)}
.premium-close{position:absolute;right:16px;top:12px;z-index:2;width:42px;height:42px;border:0;border-radius:50%;background:#302a31;color:#fff;font-size:28px}
.premium-scroll{min-height:0;flex:1 1 auto;padding:34px;overflow:auto;max-height:calc(100dvh - 44px)}
.premium-expanded .premium-scroll{max-height:calc(100dvh - 166px)}
.premium-studio-dialog h2{font-family:inherit;font-size:32px;font-weight:700;line-height:1.3;color:#fff;margin:8px 48px 30px 0;letter-spacing:-.6px}
.premium-song{display:grid;grid-template-columns:150px minmax(0,1fr);gap:26px;align-items:center;padding-bottom:30px;margin-bottom:28px;border-bottom:1px solid #39333a}
.premium-song>img{width:100%;aspect-ratio:1;border-radius:22px;object-fit:cover}.premium-song h3{font-size:24px;line-height:1.4;font-weight:650;color:#fff;margin:0 0 10px;overflow-wrap:anywhere}.premium-song p{font-size:14px;color:#ada6af;margin:0 0 12px}
.premium-play{display:inline-flex;align-items:center;gap:10px;min-height:44px;background:transparent;border:0;color:#ff7664;padding:0}.premium-play .playback-icon{width:22px;height:22px}
.premium-invitation{background:#272128;border:1px solid #382c35;border-radius:22px;padding:26px}.premium-kicker{font-size:13px;color:#ffa898;margin:0 0 18px}.premium-invitation h3{font-size:25px;font-weight:650;color:#fff;margin:0 0 14px}.premium-invitation>p:not(.premium-kicker){font-size:16px;line-height:1.9;color:#c2b8c3;margin:0 0 22px}
.premium-primary{text-decoration:none;display:flex;align-items:center;justify-content:center;gap:24px;min-height:52px;border:0;border-radius:30px;background:#f93822;color:#fff;font-size:15px;font-weight:650;padding:13px 24px;cursor:pointer}.premium-invitation .premium-primary{text-decoration:none;width:100%}.premium-primary:disabled{background:#51454d;color:#c1b1bb;cursor:default}.premium-primary:hover:not(:disabled){background:#de2b19}.premium-primary:focus-visible,.premium-gallery button:focus-visible,.premium-close:focus-visible,.premium-back:focus-visible{outline:2px solid #ffb3a6;outline-offset:4px}
.premium-back{border:0;background:transparent;color:#c2b2bf;min-height:36px;padding:0;font-size:13px;margin:0 0 18px}.premium-expanded h2{margin-bottom:12px}.premium-expanded .premium-kicker{margin-bottom:10px}.premium-description{font-size:14px;color:#b3a8b3;margin:0 0 24px}
.premium-plans{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;border:0;padding:0;margin:0}.premium-plans legend{font-size:13px;color:#e1d7df;padding:0 0 12px}
.premium-plan{position:relative;display:flex;flex-direction:column;align-items:flex-start;gap:16px;padding:22px 18px;border:1px solid #50454e;border-radius:18px;background:#211c23;cursor:pointer}.premium-plan.selected{background:#382322;border-color:#ff7968;box-shadow:inset 0 0 0 1px #ff7968}.premium-plan:focus-within{outline:2px solid #ffb3a6;outline-offset:3px}.premium-plan input{position:absolute;top:22px;right:16px;width:16px;height:16px;margin:0;accent-color:#f93822}.premium-plan-name{font-size:15px;padding-right:20px;color:#f1e9ee}.premium-price{display:block;font-size:29px;line-height:1.2;font-weight:700;letter-spacing:-1px;color:#fff;white-space:nowrap}.premium-price small{font-size:14px;margin-left:4px;font-weight:500}.premium-plan-description{font-size:13px;line-height:1.8;color:#c0b4bf}.premium-features{display:flex;flex-direction:column;gap:10px;margin-top:auto;font-size:12px;color:#cdbfca}.premium-features>span::before{content:'✓';margin-right:8px;color:#ff9f8e}
.premium-price-note{font-size:12px;line-height:1.8;color:#b5a3b0;margin:16px 0 28px}.premium-gallery-heading{display:flex;justify-content:space-between;gap:16px;align-items:baseline}.premium-gallery-heading h3{font-size:19px;font-weight:600;color:#fff;margin:0 0 16px}.premium-gallery-heading>span{font-size:11px;color:#a99aa6}.premium-gallery{display:grid;grid-template-columns:1fr 1fr;gap:16px}.premium-gallery button{position:relative;text-align:left;overflow:hidden;border:1px solid #41343e;border-radius:18px;background:#211b22;color:#fff;padding:0}.premium-gallery img{display:block;width:100%;aspect-ratio:16/10;object-fit:cover}.premium-gallery button>span{display:block;padding:18px 40px 18px 18px}.premium-gallery strong{font-size:18px;font-weight:600;display:block;margin-bottom:8px}.premium-gallery small{font-size:12px;line-height:1.7;color:#b9aab6;display:block}.premium-gallery i{position:absolute;bottom:22px;right:18px;font-style:normal;color:#ff9f8e}
.premium-contact{display:flex;align-items:center;flex-wrap:wrap;gap:12px 20px;margin-top:24px;font-size:13px;color:#c8b6c3}.premium-contact a{color:#ffb4a4;overflow-wrap:anywhere}.premium-contact button{background:transparent;color:#ffd0c4;border:1px solid #71544f;border-radius:22px;padding:8px 14px}.premium-contact button:disabled{opacity:.45}.premium-contact small{display:block;width:100%;font-size:12px;color:#ac9ba8}
.premium-summary{flex-shrink:0;position:sticky;bottom:0;display:flex;justify-content:space-between;align-items:center;gap:20px;margin:0;padding:20px 34px;background:#211c23;border-top:1px solid #493a44;max-width:none;width:100%}.premium-summary>div{min-width:0}.premium-summary>div>span{display:block;font-size:12px;color:#bcaab8;margin-bottom:6px}.premium-summary strong{font-size:15px;color:#fff;line-height:1.6}.premium-summary strong span{color:#ffad9d;white-space:nowrap}.premium-summary small{font-size:12px;color:#d5c4d0}.premium-summary .premium-primary{text-decoration:none;flex-shrink:0}.premium-notice{padding:16px 0 0;font-size:13px;line-height:1.8;color:#ffb7a8}.premium-full-image{display:block;max-width:100%;max-height:65dvh;margin:20px auto;object-fit:contain;border-radius:12px}.premium-image-note{text-align:center;color:#bdaebb;font-size:12px}
@media(max-width:640px){.premium-studio-dialog,.premium-studio-dialog.premium-expanded{width:calc(100% - 20px);max-height:calc(100dvh - 20px);border-radius:22px}.premium-scroll{padding:26px 20px;max-height:calc(100dvh - 24px)}.premium-expanded .premium-scroll{max-height:calc(100dvh - 166px)}.premium-studio-dialog h2{font-size:25px;margin-right:34px}.premium-song{grid-template-columns:100px minmax(0,1fr);gap:18px}.premium-song h3{font-size:20px}.premium-song p{font-size:12px}.premium-invitation{padding:22px 18px}.premium-invitation h3{font-size:22px}.premium-invitation>p:not(.premium-kicker){font-size:14px}.premium-plans{grid-template-columns:1fr}.premium-plan{gap:10px;padding:18px}.premium-price{font-size:30px}.premium-features{flex-direction:row;flex-wrap:wrap;gap:8px 14px}.premium-gallery{grid-template-columns:1fr}.premium-gallery-heading{align-items:flex-start;flex-direction:column;gap:0;margin-bottom:14px}.premium-gallery-heading h3{margin-bottom:6px}.premium-summary{padding:16px 20px;gap:12px;align-items:stretch;flex-direction:column}.premium-summary>div>span{display:none}.premium-summary strong{font-size:14px}.premium-summary .premium-primary{text-decoration:none;min-height:46px;width:100%}.premium-price-note{margin-bottom:24px}}
</style>
