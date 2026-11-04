<script setup lang="ts">
import { useId } from 'vue'
import { t } from '../../../shared/i18n'
import { vocalChoices,type VocalGender } from '../model/vocals'
defineProps<{modelValue:VocalGender}>()
const emit=defineEmits<{'update:modelValue':[value:VocalGender]}>(),group=useId()
</script>
<template><fieldset class="vocal-field"><legend>{{ t('演唱声音') }}</legend><div class="vocal-choices"><label v-for="choice in vocalChoices" :key="choice.value" class="vocal-choice"><input type="radio" :name="'vocal-'+group" :value="choice.value" :checked="modelValue===choice.value" @change="emit('update:modelValue',choice.value)"><span><i aria-hidden="true">{{ modelValue===choice.value?'✓':'' }}</i>{{ t(choice.label) }}</span></label></div><p class="save-info">{{ t('选择希望的演唱声音，实际效果以生成结果为准。') }}</p></fieldset></template>
