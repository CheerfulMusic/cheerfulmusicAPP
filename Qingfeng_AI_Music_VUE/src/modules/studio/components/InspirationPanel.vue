<script setup lang="ts">
import { computed, ref } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { inspirationGroups } from '../model/inspiration-ideas'

defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ choose: [description: string] }>()
const groupIndex = ref(0)
const activeIndex = ref(0)
const ideas = computed(() => inspirationGroups[groupIndex.value]!.map(([labelZh, labelEn, storyZh, storyEn]) =>
  locale.value === 'en' ? { label: labelEn, story: storyEn } : { label: labelZh, story: storyZh },
))
const preview = computed(() => ideas.value[activeIndex.value]!.story)

function nextGroup() {
  groupIndex.value = (groupIndex.value + 1) % inspirationGroups.length
  activeIndex.value = 0
}

function choose(index: number) {
  activeIndex.value = index
  emit('choose', ideas.value[index]!.story)
}
</script>

<template>
  <section class="inspiration-strip" aria-labelledby="inspiration-strip-title">
    <div class="inspiration-strip-toolbar">
      <strong id="inspiration-strip-title">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 18h6m-5 3h4m-6-6c0-2-3-3-3-6a7 7 0 0 1 14 0c0 3-3 4-3 6"/></svg>
        {{ t('找灵感') }}
      </strong>
      <div class="inspiration-strip-options">
        <button v-for="(idea, index) in ideas" :key="idea.label" type="button" class="inspiration-strip-option"
          :aria-pressed="index === activeIndex" :aria-label="`${idea.label} · ${t('用这个灵感')}`"
          :disabled="disabled" @click="choose(index)">{{ idea.label }}</button>
      </div>
      <button type="button" class="inspiration-strip-refresh" :disabled="disabled" @click="nextGroup">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 5v5h-5M4 19v-5h5M5.5 9a7 7 0 0 1 12-3L20 10M4 14l2.5 4a7 7 0 0 0 12-3"/></svg>
        {{ t('换一组') }}
      </button>
    </div>
    <p class="inspiration-strip-preview" aria-live="polite">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z"/></svg>
      <span>{{ preview }}</span>
    </p>
  </section>
</template>

<style scoped>
.inspiration-strip { margin-top:10px; border:1px solid #f0dedf; border-radius:15px; background:#fffafa; }
.inspiration-strip-toolbar { display:grid; grid-template-columns:auto minmax(0,1fr) auto; align-items:center; gap:12px; padding:6px 12px; }
.inspiration-strip-toolbar strong { display:flex; align-items:center; gap:7px; padding-inline-end:12px; border-inline-end:1px solid #f0dedf; color:#302730; white-space:nowrap; font-size:14px; }
.inspiration-strip-options { display:flex; flex-wrap:wrap; gap:6px 8px; min-width:0; }
.inspiration-strip button { min-height:33px; border:1px solid #ead8dc; border-radius:999px; padding:4px 12px; color:#493e48; background:#fffdfd; font:inherit; font-size:13px; line-height:1.3; cursor:pointer; }
.inspiration-strip .inspiration-strip-refresh { display:flex; align-items:center; gap:5px; color:var(--accent-text,#be3829); font-weight:650; white-space:nowrap; }
.inspiration-strip button[aria-pressed=true],.inspiration-strip button:hover:not(:disabled) { border-color:#efb4b1; background:#fff0ed; color:#a12e29; }
.inspiration-strip button:focus-visible { outline:2px solid #a12e29; outline-offset:2px; }
.inspiration-strip button:disabled { opacity:.62; cursor:default; }
.inspiration-strip svg { width:16px; height:16px; flex:none; fill:none; stroke:currentColor; stroke-width:1.65; stroke-linecap:round; stroke-linejoin:round; }
.inspiration-strip-toolbar strong svg,.inspiration-strip-preview svg { color:#cc453b; }
.inspiration-strip-refresh svg { width:13px; height:13px; }
.inspiration-strip-preview { display:flex; align-items:baseline; gap:8px; margin:0; padding:8px 12px; border-top:1px solid #f4e4e7; border-radius:0 0 14px 14px; background:#fff1f4; color:#57444d; font-size:13px; line-height:1.5; }
.inspiration-strip-preview svg { align-self:flex-start; margin-top:2px; }
.inspiration-strip-preview span { min-width:0; overflow-wrap:anywhere; }
@media (max-width:600px) {
  .inspiration-strip-toolbar { grid-template-columns:minmax(0,1fr) auto; gap:7px 8px; padding:7px 9px; }
  .inspiration-strip-toolbar strong { border:0; padding:0; }
  .inspiration-strip-options { grid-column:1 / -1; grid-row:2; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); }
  .inspiration-strip button { min-height:36px; padding-inline:6px; overflow-wrap:anywhere; }
  .inspiration-strip .inspiration-strip-refresh { padding-inline:10px; }
  .inspiration-strip-preview { padding:8px 9px; }
}
</style>
