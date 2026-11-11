<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../../../shared/i18n'

const props = defineProps<{ hasText: boolean; disabled: boolean }>()
const expanded = defineModel<boolean>('expanded', { required: true })
const emit = defineEmits<{ choose: [description: string] }>()

const categories = [
  { label: '写给自己', icon: 'M12 20 4.7 13a4.4 4.4 0 0 1 6.2-6.2L12 8l1.1-1.2a4.4 4.4 0 0 1 6.2 6.2L12 20Z' },
  { label: '想念一个人', icon: 'M4 6h16v12H4zM8 11a1 1 0 1 0 0-2 1 1 0 0 0 0 2Zm12 4-4-4-4 4-2-2-5 5' },
  { label: '从一个画面开始', icon: 'M7 4h10l3 5v8H4V9l3-5Zm-3 9h16m-12 1h1m6 0h1M7 17v3m10-3v3' },
] as const

const groups = [
  [
    {
      title: '今天终于可以松口气',
      preview: '把白天没说出口的「我累了」唱给自己。',
      description: '想写一首关于终于能松口气的歌。关上家门的那一刻，把白天那句没说出口的「我累了」唱给自己。',
    },
    {
      title: '老朋友发来一张合照',
      preview: '很久没见的人，突然发来一张以前的合照。',
      description: '想写一首关于老朋友的歌。从一张突然收到的合照写起，唱唱很久没见的人再次聊起来是什么感觉。',
    },
    {
      title: '雨后最后一班公交',
      preview: '雨停了，车窗外的路灯一盏盏亮起。',
      description: '想写一首关于雨后末班公交的歌。车窗上还挂着雨水，窗外路灯一盏盏亮起来，我终于能慢下来。',
    },
  ],
  [
    {
      title: '走出门去晒太阳',
      preview: '今天没有特别的计划，只想在阳光下走一走。',
      description: '想写一首关于独自散步的歌。今天没有计划，只想绕过平时匆匆路过的街口，在阳光下慢慢走。',
    },
    {
      title: '桌上还留着一碗热汤',
      preview: '到家时已经很晚了，厨房里还给我留着一碗汤。',
      description: '想写一首关于晚归的歌。到家时已经很晚，厨房里还有一碗汤。平时不常说的关心，就藏在这些小事里。',
    },
    {
      title: '第一次看见海',
      preview: '还没走到沙滩，风里已经有了咸味。',
      description: '想写一首关于第一次看见海的歌。还没走到沙滩，风里已经有了咸味，海比我在照片里见过的都大。',
    },
  ],
  [
    {
      title: '给三年后的我',
      preview: '把现在舍不得放下的事，留给未来的自己回答。',
      description: '想写一首给三年后的自己的歌。我想问现在舍不得放下的事，三年后还会那么重要吗？',
    },
    {
      title: '那句打了又删的消息',
      preview: '聊天框亮了又暗，还是没把「我想你了」发出去。',
      description: '想写一首关于一条没发出去的消息的歌。聊天框亮了又暗，「我想你了」打了又删，最后只留下一个空白。',
    },
    {
      title: '在陌生城市醒来',
      preview: '拉开窗帘，街上第一家早餐店刚刚亮灯。',
      description: '想写一首关于初到陌生城市的歌。清早拉开窗帘，街上第一家早餐店刚刚亮灯，生活似乎也可以从这里重新开始。',
    },
  ],
] as const

const groupIndex = ref(0)
const ideas = computed(() => groups[groupIndex.value]!.map((idea, index) => ({ ...idea, category: categories[index]! })))
const actionLabel = computed(() => t(props.hasText ? '追加到描述' : '用这个灵感'))
</script>

<template>
  <section class="quick-inspiration" :class="{ expanded }" aria-labelledby="quick-inspiration-toggle">
    <div class="inspiration-toolbar">
      <button id="quick-inspiration-toggle" type="button" class="inspiration-toggle"
        :aria-expanded="expanded" aria-controls="quick-inspiration-ideas" :disabled="disabled"
        @click="expanded = !expanded">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 18h6m-5 3h4m-6-6c0-2-3-3-3-6a7 7 0 0 1 14 0c0 3-3 4-3 6"/></svg>
        {{ t('找灵感') }}
        <svg class="inspiration-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
      </button>
      <button v-if="expanded" type="button" class="inspiration-refresh" :disabled="disabled"
        @click="groupIndex = (groupIndex + 1) % groups.length">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 5v5h-5M4 19v-5h5M5.5 9a7 7 0 0 1 12-3L20 10M4 14l2.5 4a7 7 0 0 0 12-3"/></svg>
        {{ t('换一组') }}
      </button>
    </div>
    <div v-show="expanded" id="quick-inspiration-ideas" class="inspiration-ideas" aria-live="polite">
      <article v-for="idea in ideas" :key="idea.title" class="idea-card">
        <div class="idea-category">
          <span><svg viewBox="0 0 24 24" aria-hidden="true"><path :d="idea.category.icon"/></svg></span>
          {{ t(idea.category.label) }}
        </div>
        <h3>{{ t(idea.title) }}</h3>
        <p>{{ t(idea.preview) }}</p>
        <button type="button" class="idea-use" :disabled="disabled" :aria-label="`${actionLabel} · ${t(idea.title)}`"
          @click="emit('choose', t(idea.description))">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
          {{ actionLabel }}
        </button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.quick-inspiration { margin-top:15px; padding:4px 12px; border:1px solid #f0dedf; border-radius:16px; background:#fff6f4; }
.quick-inspiration.expanded { padding:12px 14px 14px; }
.inspiration-toolbar { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.quick-inspiration button { display:inline-flex; align-items:center; gap:6px; min-height:36px; font:inherit; }
.quick-inspiration svg { width:18px; height:18px; flex:none; fill:none; stroke:currentColor; stroke-width:1.65; stroke-linecap:round; stroke-linejoin:round; }
.quick-inspiration .inspiration-toggle { padding:3px; border-radius:8px; color:var(--ink); font-size:15px; font-weight:700; }
.inspiration-toggle svg { color:var(--accent-text); }
.inspiration-toggle .inspiration-chevron { width:13px; height:13px; color:var(--muted); }
.inspiration-toggle[aria-expanded=true] .inspiration-chevron { transform:rotate(180deg); }
.quick-inspiration .inspiration-refresh { padding:4px 10px; border:1px solid #efcfcc; border-radius:999px; color:var(--accent-text); background:#fffafa; font-size:12px; font-weight:650; }
.inspiration-refresh svg,.idea-use svg { width:14px; height:14px; }
.inspiration-ideas { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin-top:10px; }
.idea-card { display:flex; flex-direction:column; min-width:0; padding:11px 12px 7px; border:1px solid var(--line); border-radius:13px; background:var(--surface); }
.idea-category { display:flex; align-items:center; gap:7px; color:#694b48; font-size:12px; }
.idea-category span { display:grid; place-items:center; width:26px; height:26px; border-radius:8px; flex:none; color:var(--accent-text); background:#fff0ed; }
.idea-card:nth-child(2) .idea-category span { color:#955049; background:#f9eceb; }
.idea-card:nth-child(3) .idea-category span { color:#75506d; background:#f5edf5; }
.idea-category svg { width:15px; height:15px; }
.idea-card h3 { margin:7px 0 4px; color:var(--ink); font-size:16px; line-height:1.4; }
.idea-card p { margin:0 0 4px; color:var(--muted); font-size:12px; line-height:1.6; }
.quick-inspiration .idea-use { align-self:flex-start; margin:auto 0 0 -5px; padding:3px 5px; border-radius:8px; color:var(--accent-text); font-size:12px; font-weight:700; text-align:left; }
.quick-inspiration button:hover:not(:disabled) { background:#ffece6; }
.quick-inspiration button:disabled { opacity:.6; }
@media (max-width:700px) {
  .quick-inspiration.expanded { padding:5px 8px 9px; }
  .quick-inspiration button { min-height:44px; }
  .inspiration-ideas { grid-template-columns:1fr; gap:6px; margin-top:3px; }
  .idea-card { display:grid; grid-template-columns:minmax(0,1fr) auto; column-gap:5px; padding:7px 8px; }
  .idea-category,.idea-card h3,.idea-card p { grid-column:1; }
  .idea-category { gap:5px; font-size:11px; }
  .idea-category span { width:20px; height:20px; border-radius:5px; }
  .idea-category svg { width:12px; height:12px; }
  .idea-card h3 { margin:3px 0 2px; font-size:14px; }
  .idea-card p { margin:0; }
  .quick-inspiration .idea-use { grid-column:2; grid-row:1 / span 3; align-self:center; max-width:88px; margin:0; }
  .idea-use svg { display:none; }
}
</style>
