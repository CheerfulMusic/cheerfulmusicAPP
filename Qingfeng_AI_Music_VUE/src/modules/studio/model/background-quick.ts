import { shallowReactive } from 'vue'
import type { QuickCreation } from '../api/quick-creation'

interface BackgroundQuick {
  owner: string
  workId: string
  flowId?: string
  language?: string
  status?: QuickCreation['status']
}

export const backgroundQuick = shallowReactive(new Map<string, BackgroundQuick>())

export function followQuickInBackground(task: BackgroundQuick) {
  if (task.owner && task.workId) backgroundQuick.set(task.workId, task)
}
