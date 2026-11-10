/** 参考音乐语义化调用：只发数值特征与文字，音频字节永远不出浏览器。失败一律返回 undefined 走降级。 */
import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import { signedIn } from '../../home/model/hot-songs'
import { styleFromInspiration } from '../../studio/model/suggestions'
import type { AudioFeatures } from '../model/audio-features'
import type { ReferenceInsight } from '../model/assistant'

export interface ReferenceAnalysisInput {
  text: string
  fileName: string
  duration: number
  locale: 'zh-CN' | 'en'
  features: AudioFeatures
}

interface ReferenceAnalysisResponse {
  style?: unknown; mood?: unknown; tempo?: unknown; tempoVerified?: unknown
  inferredInstrumentation?: unknown; negativeTags?: unknown; summary?: unknown; source?: unknown
}

function textList(value: unknown, limit: number): string[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0).slice(0, limit)
}

/** 只接受形状正确的响应；任何异常都退回基础信息，不阻断创作。 */
export function readReferenceInsight(value: unknown): ReferenceInsight | undefined {
  if (!value || typeof value !== 'object') return undefined
  const body = value as ReferenceAnalysisResponse
  if (typeof body.style !== 'string' || typeof body.summary !== 'string') return undefined
  const tempo = typeof body.tempo === 'number' && Number.isFinite(body.tempo) && body.tempo > 0 ? body.tempo : 0
  return {
    style: styleFromInspiration(body.style),
    mood: typeof body.mood === 'string' ? body.mood.slice(0, 20) : '',
    tempo,
    tempoVerified: body.tempoVerified === true && tempo > 0,
    instrumentation: textList(body.inferredInstrumentation, 4),
    negativeTags: textList(body.negativeTags, 4),
    summary: body.summary.slice(0, 120),
    source: body.source === 'AI' ? 'AI' : 'RULE',
  }
}

export async function requestReferenceAnalysis(input: ReferenceAnalysisInput): Promise<ReferenceInsight | undefined> {
  if (!signedIn()) return undefined
  const features = input.features
  try {
    await ensureCreator()
    const body = {
      text: input.text.slice(0, 400),
      fileName: input.fileName.slice(0, 160),
      duration: input.duration,
      locale: input.locale,
      features: {
        bpm: features.bpm, bpmConfidence: features.bpmConfidence, rms: features.rms, peak: features.peak,
        dynamicRange: features.dynamicRange, low: features.bands.low, mid: features.bands.mid, high: features.bands.high,
        brightness: features.brightness, noisiness: features.noisiness,
        duration: features.duration, analyzedSeconds: features.analyzedSeconds,
      },
    }
    return readReferenceInsight(await requestJson<unknown>('app/agent/reference', 'POST', body, 14000))
  } catch { return undefined }
}
