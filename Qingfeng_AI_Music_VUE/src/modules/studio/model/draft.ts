import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 仅为浏览器草稿，不代表服务端作品或已确认的生成快照。 */
export const useCreationDraft = defineStore('creation-draft', () => {
  const recipient = ref('')
  const story = ref('')
  function clear() { recipient.value = ''; story.value = '' }
  return { recipient, story, clear }
})
