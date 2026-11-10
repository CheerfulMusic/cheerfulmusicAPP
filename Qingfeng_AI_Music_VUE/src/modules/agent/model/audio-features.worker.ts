/** 音频分析 Worker：只在后台跑纯 DSP，不接触 DOM、不上传任何数据。 */
import { extractFeatures, mixToMono, resampleTo, TARGET_SAMPLE_RATE } from './audio-features'

interface AnalyzeRequest { channels: Float32Array[]; sampleRate: number; duration: number }
type AnalyzeResponse = { ok: true; features: ReturnType<typeof extractFeatures> } | { ok: false; message: string }

const scope = self as unknown as {
  onmessage: ((event: MessageEvent<AnalyzeRequest>) => void) | null
  postMessage: (message: AnalyzeResponse, transfer?: Transferable[]) => void
}

scope.onmessage = event => {
  try {
    const { channels, sampleRate, duration } = event.data
    if (!Array.isArray(channels) || channels.length === 0 || !Number.isFinite(sampleRate) || sampleRate <= 0) {
      throw new Error('invalid audio payload')
    }
    const mono = mixToMono(channels)
    const data = sampleRate > TARGET_SAMPLE_RATE ? resampleTo(mono, sampleRate, TARGET_SAMPLE_RATE) : mono
    const features = extractFeatures(data, Math.min(sampleRate, TARGET_SAMPLE_RATE), { duration })
    scope.postMessage({ ok: true, features })
  } catch (error) {
    scope.postMessage({ ok: false, message: error instanceof Error ? error.message : 'analysis failed' })
  }
}
