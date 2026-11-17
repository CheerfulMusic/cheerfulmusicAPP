<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { t } from '../../../shared/i18n'
import { saveAudioNote, type AudioAsset } from '../api/detail'

const props = defineProps<{ asset: AudioAsset; disabled?: boolean }>()
const emit = defineEmits<{ updated: [AudioAsset] }>()
const editing = ref(false), draft = ref(''), saving = ref(false), error = ref('')
const field = ref<HTMLTextAreaElement>()
watch(() => props.asset.id, () => { editing.value = false; error.value = '' })
function edit() { draft.value = props.asset.note || ''; error.value = ''; editing.value = true; void nextTick(() => field.value?.focus()) }
function cancel() { editing.value = false; error.value = '' }
async function save() {
  if (saving.value || props.disabled) return
  const id = props.asset.id
  saving.value = true; error.value = ''
  try {
    const saved = await saveAudioNote(props.asset.workId, id, draft.value, props.asset.noteVersion || 0)
    if (id !== props.asset.id) return
    emit('updated', saved); editing.value = false
  } catch (e) { if (id === props.asset.id) error.value = e instanceof Error ? e.message : '备注保存失败，请重试。' }
  finally { saving.value = false }
}
</script>
<template>
  <div class="audio-version-note">
    <form v-if="editing" @submit.prevent="save">
      <label :for="`audio-note-${asset.id}`">{{ t('版本备注（仅自己可见）') }}</label>
      <textarea ref="field" :id="`audio-note-${asset.id}`" v-model="draft" maxlength="500" rows="2" :disabled="saving || disabled" :placeholder="t('例如：婚礼入场用，副歌更轻快')" />
      <div class="audio-note-controls">
        <small>{{ draft.length }}/500</small>
        <button type="button" class="text-button" :disabled="saving || disabled" @click="cancel">{{ t('取消') }}</button>
        <button type="submit" class="button secondary" :disabled="saving || disabled">{{ saving?t('保存中…'):t('保存备注') }}</button>
      </div>
    </form>
    <template v-else>
      <p v-if="asset.note" class="audio-note-text">{{ asset.note }}</p>
      <button class="text-button audio-note-edit" :disabled="disabled" @click="edit">{{ asset.note?t('编辑备注'):t('添加备注') }}</button>
    </template>
    <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
  </div>
</template>
