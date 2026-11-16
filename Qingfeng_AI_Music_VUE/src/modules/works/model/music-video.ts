export type VideoResolution = '480P' | '720P' | '1080P'
export interface VideoShot { prompt: string; seconds: number; imageId: string | null; imageSeconds: number }
export interface VideoPlan { title: string; ratio: string; resolution: VideoResolution; durationMillis: number; shots: VideoShot[] }
export const videoRates: Record<VideoResolution, number> = { '480P': 1, '720P': 2, '1080P': 3 }
export function videoTimeline(durationMillis: number): VideoShot[] {
  const seconds = Math.ceil(durationMillis / 1000)
  if (!Number.isFinite(seconds) || seconds < 2 || seconds > 3600) throw new Error('暂不支持该音频时长。')
  return Array.from({ length: Math.ceil(seconds / 30) }, (_, i) => ({ prompt: '', seconds: Math.min(30, seconds - i * 30), imageId: null, imageSeconds: 0 }))
}
export function videoCost(plan: VideoPlan) {
  const generatedSeconds = plan.shots.reduce((sum, s) => sum + Math.max(0, s.seconds - s.imageSeconds), 0)
  return { generatedSeconds, imageSeconds: plan.shots.reduce((sum, s) => sum + s.imageSeconds, 0), credits: generatedSeconds * videoRates[plan.resolution] }
}
export function sceneLabel(index: number, count: number) {
  return ['故事开场', '人物与场景建立', '故事发展', '情绪冲突', '高潮', '结局与收束'][count === 1 ? 0 : Math.round(index * 5 / (count - 1))]
}
