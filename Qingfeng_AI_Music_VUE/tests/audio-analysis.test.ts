import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  AudioAnalysisError, analyzeAudioFile, cancelAnalysis, clearAnalysisCache, forgetAnalysis,
  type AnalysisDeps,
} from '../src/modules/agent/model/audio-analysis'
import { extractFeatures } from '../src/modules/agent/model/audio-features'

const RATE = 22050

function clickTrack(bpm: number, seconds: number) {
  const data = new Float32Array(Math.round(seconds * RATE))
  const interval = 60 / bpm
  for (let beat = 0; beat * interval < seconds; beat++) {
    const start = Math.round(beat * interval * RATE)
    for (let index = 0; index < 220 && start + index < data.length; index++) {
      data[start + index]! += Math.exp(-index / 28) * Math.sin((2 * Math.PI * 900 * index) / RATE)
    }
  }
  return data
}

function audioFile(name = 'ref.mp3') {
  return new File([new Uint8Array([1, 2, 3, 4])], name, { type: 'audio/mpeg' })
}

class FakeWorker {
  static last: FakeWorker | undefined
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: ErrorEvent) => void) | null = null
  terminated = false
  constructor(private behavior: 'ok' | 'error' | 'hang' = 'ok') { FakeWorker.last = this }
  postMessage(message: unknown) {
    if (this.behavior === 'hang') return
    if (this.behavior === 'error') { queueMicrotask(() => this.onerror?.({} as ErrorEvent)); return }
    const payload = message as { channels: Float32Array[]; sampleRate: number; duration: number }
    queueMicrotask(() => {
      const features = extractFeatures(payload.channels[0]!, payload.sampleRate, { duration: payload.duration })
      this.onmessage?.({ data: { ok: true, features } } as MessageEvent)
    })
  }
  terminate() { this.terminated = true }
}

function deps(overrides: Partial<AnalysisDeps> = {}): AnalysisDeps {
  return {
    digest: async () => 'hash-1',
    decode: async () => ({ channels: [clickTrack(120, 8)], sampleRate: RATE, duration: 8 }),
    createWorker: () => new FakeWorker('ok'),
    ...overrides,
  }
}

afterEach(() => { clearAnalysisCache(); cancelAnalysis(); FakeWorker.last = undefined })

describe('参考音频分析编排', () => {
  it('runs the real DSP through the worker and returns measured features', async () => {
    const result = await analyzeAudioFile(audioFile(), deps())
    expect(result.key).toBe('hash-1')
    expect(result.features.bpm).toBeGreaterThanOrEqual(118)
    expect(result.features.bpm).toBeLessThanOrEqual(122)
    expect(result.features.analyzedSeconds).toBeCloseTo(8, 1)
    expect(FakeWorker.last?.terminated).toBe(true)
  })

  it('reuses the cached result for the same file and never mixes different files', async () => {
    const decode = vi.fn(async () => ({ channels: [clickTrack(120, 8)], sampleRate: RATE, duration: 8 }))
    await analyzeAudioFile(audioFile('a.mp3'), deps({ decode }))
    await analyzeAudioFile(audioFile('a-copy.mp3'), deps({ decode }))
    expect(decode).toHaveBeenCalledTimes(1)

    const other = vi.fn(async () => ({ channels: [clickTrack(100, 8)], sampleRate: RATE, duration: 8 }))
    const second = await analyzeAudioFile(audioFile('b.mp3'), deps({ digest: async () => 'hash-2', decode: other }))
    expect(other).toHaveBeenCalledTimes(1)
    expect(second.features.bpm).toBeGreaterThanOrEqual(98)
    expect(second.features.bpm).toBeLessThanOrEqual(102)
  })

  it('drops a cached entry when the reference track is removed', async () => {
    const decode = vi.fn(async () => ({ channels: [clickTrack(120, 8)], sampleRate: RATE, duration: 8 }))
    await analyzeAudioFile(audioFile(), deps({ decode }))
    forgetAnalysis('hash-1')
    await analyzeAudioFile(audioFile(), deps({ decode }))
    expect(decode).toHaveBeenCalledTimes(2)
  })

  it('reports a decode failure without throwing raw errors', async () => {
    await expect(analyzeAudioFile(audioFile(), deps({ decode: async () => { throw new Error('boom') } })))
      .rejects.toMatchObject({ code: 'DECODE_FAILED' })
  })

  it('refuses files above the 20MB limit before decoding', async () => {
    const file = audioFile()
    Object.defineProperty(file, 'size', { value: 21 * 1024 * 1024 })
    const decode = vi.fn()
    await expect(analyzeAudioFile(file, deps({ decode }))).rejects.toMatchObject({ code: 'TOO_LARGE' })
    expect(decode).not.toHaveBeenCalled()
  })

  it('turns a worker crash into WORKER_FAILED', async () => {
    await expect(analyzeAudioFile(audioFile(), deps({ createWorker: () => new FakeWorker('error') })))
      .rejects.toMatchObject({ code: 'WORKER_FAILED' })
  })

  it('times out instead of hanging forever', async () => {
    await expect(analyzeAudioFile(audioFile(), deps({ createWorker: () => new FakeWorker('hang'), timeoutMs: 40 })))
      .rejects.toMatchObject({ code: 'TIMEOUT' })
  })

  it('cancels an in-flight analysis when the file is replaced', async () => {
    let worker: FakeWorker | undefined
    const pending = analyzeAudioFile(audioFile(), deps({ createWorker: () => (worker = new FakeWorker('hang')) }))
    for (let attempt = 0; attempt < 40 && worker === undefined; attempt++) await new Promise(resolve => setTimeout(resolve, 5))
    cancelAnalysis()
    await expect(pending).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(worker?.terminated).toBe(true)
  })

  it('falls back to the main thread when workers are unavailable', async () => {
    const result = await analyzeAudioFile(audioFile(), deps({ createWorker: () => undefined }))
    expect(result.features.bpm).toBeGreaterThanOrEqual(118)
    expect(result.features.bpm).toBeLessThanOrEqual(122)
  })

  it('works without hashing but does not cache', async () => {
    const decode = vi.fn(async () => ({ channels: [clickTrack(120, 8)], sampleRate: RATE, duration: 8 }))
    const first = await analyzeAudioFile(audioFile(), deps({ digest: async () => undefined, decode }))
    const second = await analyzeAudioFile(audioFile(), deps({ digest: async () => undefined, decode }))
    expect(first.key).toBeUndefined()
    expect(second.key).toBeUndefined()
    expect(decode).toHaveBeenCalledTimes(2)
  })

  it('keeps error codes typed for the UI to translate', () => {
    expect(new AudioAnalysisError('TIMEOUT', 'x').code).toBe('TIMEOUT')
  })
})
