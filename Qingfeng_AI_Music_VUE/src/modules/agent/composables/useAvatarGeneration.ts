import { onScopeDispose, ref } from 'vue'
import { currentCreatorId, ensureCreator, requestJson } from '../../../shared/http/api-client'
import { locale } from '../../../shared/i18n'

type AvatarStatus = 'NONE' | 'SUBMITTING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'SUPERSEDED'
interface AvatarTask { id: string | null; status: AvatarStatus; available: boolean }

/** Resume only the current creator's latest generation, including after refresh. */
export function useAvatarGeneration(refreshAvatar: () => Promise<void>, refreshCredits: () => Promise<unknown> = async () => undefined) {
  const message = ref('')
  const available = ref<boolean | null>(null)
  const tr = (zh: string, en: string) => locale.value === 'en' ? en : zh
  let version = 0, timer: ReturnType<typeof setTimeout> | undefined
  const refreshedTasks = new Set<string>()
  function stop() { version++; clearTimeout(timer); timer = undefined; message.value = ''; available.value = null }
  async function check(turn: number) {
    try {
      await ensureCreator()
      if (turn !== version) return
      const owner = currentCreatorId()
      const task = await requestJson<AvatarTask>('app/avatar/generation/latest')
      if (turn !== version || !owner || owner !== currentCreatorId()) return
      available.value = task.available
      if (task.id && ['SUCCEEDED', 'FAILED', 'SUPERSEDED'].includes(task.status)) {
        const key = `${owner}:${task.id}`
        if (!refreshedTasks.has(key)) {
          refreshedTasks.add(key)
          try { void refreshCredits().catch(() => { refreshedTasks.delete(key) }) }
          catch { refreshedTasks.delete(key) /* A balance refresh must not break task state. */ }
        }
      }
      switch (task.status) {
        case 'SUBMITTING': case 'RUNNING':
          message.value = tr('正在生成头像，完成后会自动更新。', 'Creating your avatar. It will update automatically when ready.')
          timer = setTimeout(() => { void check(turn) }, 4000); break
        case 'SUCCEEDED': message.value = ''; await refreshAvatar(); break
        case 'UNKNOWN': message.value = tr('头像任务结果及积分状态待核实，请勿重复提交。', 'The avatar result and credits need verification. Please do not submit again.'); break
        case 'FAILED': message.value = tr('头像生成未完成；如本次已扣积分，已退还。', 'Avatar generation did not complete. Any credits charged for this request have been returned.'); break
        case 'SUPERSEDED': message.value = tr('已保留你更新过的头像；如本次已扣积分，已退还。', 'Your newer avatar has been kept. Any credits charged for this request have been returned.'); break
        default: message.value = ''
      }
    } catch {
      if (turn === version) { message.value = tr('暂时无法查询头像生成进度，正在重试。', 'Unable to check avatar progress. Retrying.'); timer = setTimeout(() => { void check(turn) }, 5000) }
    }
  }
  function start() { stop(); const turn = version; void check(turn) }
  onScopeDispose(stop)
  return { message, available, start, stop }
}
