<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../../../shared/i18n'
import { customLyricLanguageName, customLyricLanguageMax, customLyricLanguagePrefix, lyricLanguageLabels } from '../../../shared/i18n/lyric-language'

defineProps<{ disabled?: boolean; compact?: boolean }>()
const language = defineModel<string>({ required: true })
const customName = computed({
  get: () => customLyricLanguageName(language.value) ?? '',
  set: name => { language.value = customLyricLanguagePrefix + name },
})
const selection = computed({
  get: () => customLyricLanguageName(language.value) === undefined ? language.value : customLyricLanguagePrefix,
  set: value => { language.value = value },
})
</script>

<template>
  <div :class="compact ? 'inspiration-language' : 'photo-field'">
    <span :class="{ 'inspiration-language-title': compact }">
      <svg v-if="compact" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a17 17 0 0 1 0 18 17 17 0 0 1 0-18Z"/></svg>
      {{ t('歌词语言') }}
    </span>
    <div class="lyric-language-controls">
      <span :class="{ 'inspiration-language-select': compact }">
        <select v-model="selection" name="lyric-language" :aria-label="t('歌词语言')" :disabled="disabled">
          <option v-for="(label, value) in lyricLanguageLabels" :key="value" :value="value">{{ t(label) }}</option>
          <option :value="customLyricLanguagePrefix">{{ t('自定义语言') }}</option>
        </select>
        <svg v-if="compact" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4"/></svg>
      </span>
      <input v-if="selection === customLyricLanguagePrefix" v-model="customName" name="custom-lyric-language"
        type="text" :maxlength="customLyricLanguageMax" :aria-label="t('自定义歌词语言')"
        :placeholder="t('例如：巴西葡萄牙语')" :disabled="disabled">
    </div>
  </div>
</template>

<style scoped>
.lyric-language-controls { display: grid; gap: 6px; min-width: 0; }
.lyric-language-controls select { width: 100%; }
.lyric-language-controls input { box-sizing: border-box; width: 100%; min-width: 0; }
.inspiration-language:has(input) { align-items: start; border-radius: 15px; }
.inspiration-language:has(input) .inspiration-language-title { margin-top: 8px; }
.inspiration-language input { max-width: 14rem; padding: 8px; border: 1px solid #e4aa98; border-radius: 10px; background: #fff; color: #563930; font: inherit; }
</style>
