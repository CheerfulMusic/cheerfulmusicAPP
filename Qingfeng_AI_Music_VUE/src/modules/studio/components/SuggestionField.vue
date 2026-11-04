<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { t } from '../../../shared/i18n'

const props = withDefaults(defineProps<{
  modelValue: string; label: string; placeholder: string; suggestions: string[]
  maxLength: number; translateValue?: boolean; required?: boolean; hint?:string; initialCount?:number; invalid?:boolean; errorId?:string
}>(), { translateValue: false, required: false, initialCount:8 })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const fieldId = useId(), expanded = ref(false)
const visible = computed(() => expanded.value ? props.suggestions : props.suggestions.slice(0, props.initialCount))
const displayValue = computed(() => props.translateValue && props.suggestions.includes(props.modelValue) ? t(props.modelValue) : props.modelValue)
function input(event: Event) {
  const value = (event.target as HTMLInputElement).value
  const preset = props.translateValue ? props.suggestions.find(item => t(item) === value) : undefined
  emit('update:modelValue', preset ?? value)
}
function choose(value: string) { emit('update:modelValue', props.translateValue ? value : t(value)) }
function selected(value: string) { return props.modelValue === (props.translateValue ? value : t(value)) }
</script>
<template>
  <div class="field suggestion-field">
    <div class="suggestion-field-heading"><label :for="fieldId">{{ label }}</label><span>{{ t('可自行输入') }}</span></div>
    <input :id="fieldId" :value="displayValue" :maxlength="maxLength" :aria-required="required || undefined" :aria-invalid="invalid||undefined" :placeholder="placeholder" :aria-describedby="[`${fieldId}-hint`,errorId].filter(Boolean).join(' ')" @input="input">
    <p :id="`${fieldId}-hint`" class="suggestion-hint">{{ t(hint || '点一个示例快速填写，或写下你自己的想法。') }}</p>
    <div class="suggestion-tags" role="group" :aria-label="label + ' · ' + t('推荐标签')">
      <button v-for="value in visible" :key="value" type="button" class="suggestion-tag" :class="{selected:selected(value)}" :aria-pressed="selected(value)" @click="choose(value)"><span v-if="selected(value)" aria-hidden="true">✓</span>{{ t(value) }}</button>
      <button v-if="suggestions.length>initialCount" type="button" class="suggestion-more" :aria-expanded="expanded" @click="expanded=!expanded">{{ expanded ? t('收起示例') : t('更多示例') }}<span aria-hidden="true">{{ expanded ? '−' : '+' }}</span></button>
    </div>
  </div>
</template>
