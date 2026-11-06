import { readonly, ref } from 'vue'

const open = ref(false)
export const loginDialogOpen = readonly(open)
export function showLoginDialog() { open.value = true }
export function hideLoginDialog() { open.value = false }
