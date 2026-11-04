import type { AudioAsset, GenerationTask } from '../api/detail'

/** 必须按试听音频匹配原始任务，不能用最新任务或当前草稿替代历史参数。 */
export function songSettings(asset:AudioAsset|undefined|null,tasks:GenerationTask[]) {
  if(!asset||asset.origin!=='GENERATED')return undefined
  return tasks.find(task=>task.assetId===asset.id)
}
