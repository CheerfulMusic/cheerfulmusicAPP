import { beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({
  signedIn: true,
  ensureCreator: vi.fn(async () => undefined),
  requestJson: vi.fn(),
}))
vi.mock('../src/modules/home/model/hot-songs', () => ({ signedIn: () => state.signedIn }))
vi.mock('../src/shared/http/api-client', () => ({ ensureCreator: state.ensureCreator, requestJson: state.requestJson }))

import { readReferenceInsight, requestReferenceAnalysis } from '../src/modules/agent/api/reference'
import type { AudioFeatures } from '../src/modules/agent/model/audio-features'

const FEATURES: AudioFeatures = {
  duration: 128, sampleRate: 22050, mono: true, bpm: 120.2, bpmConfidence: 0.92,
  rms: 0.31, peak: 0.87, dynamicRange: 0.42, bands: { low: 0.52, mid: 0.31, high: 0.17 },
  brightness: 0.22, noisiness: 0.18, energyCurve: new Array(8).fill(0.5), analyzedSeconds: 90,
}

const VALID = {
  style: '电影配乐', mood: '有画面感', tempo: 120.2, tempoVerified: true,
  inferredInstrumentation: ['弦乐', '钢琴'], negativeTags: ['电音'], summary: '偏电影的叙事方向。', source: 'AI',
}

beforeEach(() => {
  state.signedIn = true
  state.ensureCreator.mockClear()
  state.requestJson.mockReset()
})

describe('参考音乐语义化调用', () => {
  it('accepts a well-formed response and keeps the whitelist guarantee', () => {
    expect(readReferenceInsight(VALID)).toMatchObject({ style: '电影配乐', tempo: 120.2, tempoVerified: true, source: 'AI' })
    expect(readReferenceInsight({ ...VALID, style: '赛博朋克电音' })?.style).toBe('温暖民谣')
    expect(readReferenceInsight({ ...VALID, tempo: 0, tempoVerified: true })?.tempoVerified).toBe(false)
  })

  it('rejects malformed responses instead of passing them to the brief', () => {
    expect(readReferenceInsight(null)).toBeUndefined()
    expect(readReferenceInsight({ ...VALID, style: 12 })).toBeUndefined()
    expect(readReferenceInsight({ ...VALID, summary: undefined })).toBeUndefined()
  })

  it('sends only numeric features and text, never audio bytes', async () => {
    state.requestJson.mockResolvedValue(VALID)
    const insight = await requestReferenceAnalysis({ text: '想写一首给妈妈的歌', fileName: 'ref.m4a', duration: 128, locale: 'zh-CN', features: FEATURES })
    expect(insight?.style).toBe('电影配乐')
    expect(state.ensureCreator).toHaveBeenCalledTimes(1)
    const [path, method, body] = state.requestJson.mock.calls[0]!
    expect(path).toBe('app/agent/reference')
    expect(method).toBe('POST')
    const payload = body as Record<string, unknown>
    expect(JSON.stringify(payload)).not.toContain('base64')
    expect(JSON.stringify(payload)).not.toContain('data:audio')
    expect((payload.features as Record<string, number>).low).toBe(0.52)
    expect((payload.features as Record<string, number>).duration).toBe(128)
    expect(payload.text).toBe('想写一首给妈妈的歌')
  })

  it('degrades to undefined for signed-out users without calling the backend', async () => {
    state.signedIn = false
    expect(await requestReferenceAnalysis({ text: '', fileName: 'ref.mp3', duration: 10, locale: 'zh-CN', features: FEATURES })).toBeUndefined()
    expect(state.requestJson).not.toHaveBeenCalled()
  })

  it('degrades to undefined on any failure', async () => {
    state.requestJson.mockRejectedValue(new Error('502'))
    expect(await requestReferenceAnalysis({ text: '', fileName: 'ref.mp3', duration: 10, locale: 'en', features: FEATURES })).toBeUndefined()
    state.requestJson.mockResolvedValue({ nonsense: true })
    expect(await requestReferenceAnalysis({ text: '', fileName: 'ref.mp3', duration: 10, locale: 'en', features: FEATURES })).toBeUndefined()
  })
})
