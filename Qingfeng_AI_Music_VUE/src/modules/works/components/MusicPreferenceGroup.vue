<script setup lang="ts">
import { locale, t } from '../../../shared/i18n'
import type { MusicGroup } from '../api/music-settings'

defineProps<{ group: MusicGroup; selected?: string[]; disabled: boolean }>()
const emit = defineEmits<{ choose: [group: string, option: string] }>()
const label = (value: { zh: string; en: string }) => locale.value === 'en' ? value.en : value.zh
</script>

<template>
  <fieldset :disabled="disabled">
    <legend>{{ label(group) }} <small v-if="group.max > 1">{{ t('最多 {count} 项', { count: group.max }) }}</small></legend>
    <div class="music-tag-options">
      <button v-for="option in group.options" :key="option.id" type="button"
        :aria-pressed="selected?.includes(option.id) ?? false" @click="emit('choose', group.id, option.id)">
        {{ label(option) }}<span v-if="(group.id === 'instruments' || group.id === 'genre') && selected?.[0] === option.id" class="music-lead">{{ t('主') }}</span>
      </button>
    </div>
  </fieldset>
</template>
