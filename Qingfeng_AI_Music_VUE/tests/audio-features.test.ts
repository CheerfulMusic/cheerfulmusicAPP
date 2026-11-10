import { describe, expect, it } from 'vitest'
import { BPM_CONFIDENCE_THRESHOLD, extractFeatures, mixToMono, resampleTo } from '../src/modules/agent/model/audio-features'

/** 用真实合成信号驱动 DSP：短促衰减音爆模拟鼓点，带可选重音。 */
function clickTrack(bpm: number, seconds: number, sampleRate: number, options: { amp?: number; accentEvery?: number; noise?: number } = {}) {
  const length = Math.round(seconds * sampleRate)
  const data = new Float32Array(length)
  let seed = 12345
  const random = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff * 2 - 1 }
  const interval = 60 / bpm
  for (let beat = 0; beat * interval < seconds; beat++) {
    const start = Math.round(beat * interval * sampleRate)
    const amp = options.accentEvery && beat % options.accentEvery === 0 ? 1 : (options.accentEvery ? 0.45 : 1) * (options.amp ?? 1)
    for (let index = 0; index < 220 && start + index < length; index++) {
      data[start + index]! += amp * Math.exp(-index / 28) * Math.sin((2 * Math.PI * 900 * index) / sampleRate)
    }
  }
  if (options.noise) for (let index = 0; index < length; index++) data[index]! += random() * options.noise
  return data
}

function silence(seconds: number, sampleRate: number) {
  return new Float32Array(Math.round(seconds * sampleRate))
}

function noise(seconds: number, sampleRate: number) {
  let seed = 987654321
  const data = new Float32Array(Math.round(seconds * sampleRate))
  for (let index = 0; index < data.length; index++) {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    data[index] = (seed / 0x7fffffff) * 2 - 1
  }
  return data
}

function sine(frequency: number, seconds: number, sampleRate: number) {
  const data = new Float32Array(Math.round(seconds * sampleRate))
  for (let index = 0; index < data.length; index++) data[index] = Math.sin((2 * Math.PI * frequency * index) / sampleRate) * 0.6
  return data
}

const RATE = 22050

describe('浏览器端音频特征提取', () => {
  it('detects a 120 BPM click track', () => {
    const features = extractFeatures(clickTrack(120, 14, RATE), RATE)
    expect(features.bpm).toBeGreaterThanOrEqual(118)
    expect(features.bpm).toBeLessThanOrEqual(122)
    expect(features.bpmConfidence).toBeGreaterThanOrEqual(BPM_CONFIDENCE_THRESHOLD)
    expect(features.analyzedSeconds).toBeCloseTo(14, 1)
    expect(features.peak).toBeGreaterThan(0.5)
  })

  it('detects a 100 BPM click track', () => {
    const features = extractFeatures(clickTrack(100, 14, RATE), RATE)
    expect(features.bpm).toBeGreaterThanOrEqual(98)
    expect(features.bpm).toBeLessThanOrEqual(102)
    expect(features.bpmConfidence).toBeGreaterThanOrEqual(BPM_CONFIDENCE_THRESHOLD)
  })

  it('reports nothing for silence instead of inventing a tempo', () => {
    const features = extractFeatures(silence(6, RATE), RATE)
    expect(features.bpm).toBe(0)
    expect(features.bpmConfidence).toBe(0)
    expect(features.rms).toBe(0)
    expect(features.peak).toBe(0)
    expect(features.bands.low + features.bands.mid + features.bands.high).toBe(0)
  })

  it('stays below the confidence threshold for white noise', () => {
    const features = extractFeatures(noise(10, RATE), RATE)
    expect(features.bpmConfidence).toBeLessThan(BPM_CONFIDENCE_THRESHOLD)
    expect(features.bpm).toBe(0)
    expect(features.noisiness).toBeGreaterThan(0.25)
  })

  it('does not turn a steady sine into a beat', () => {
    const features = extractFeatures(sine(440, 8, RATE), RATE)
    expect(features.bpm).toBe(0)
    expect(features.bpmConfidence).toBeLessThan(BPM_CONFIDENCE_THRESHOLD)
    expect(features.brightness).toBeGreaterThan(0)
    expect(features.noisiness).toBeLessThan(0.2)
  })

  it('does not claim a tempo for weak beats buried in noise', () => {
    const features = extractFeatures(clickTrack(120, 12, RATE, { amp: 0.05, noise: 0.02 }), RATE)
    expect(features.bpmConfidence).toBeLessThan(BPM_CONFIDENCE_THRESHOLD)
  })

  it('does not double a slow tempo that falls outside the reported range', () => {
    const features = extractFeatures(clickTrack(60, 16, RATE), RATE)
    expect(features.bpm === 0 || features.bpm >= 80).toBe(true)
    expect(features.bpm).not.toBe(120)
  })

  it('keeps confidence moderate for two competing tempi', () => {
    const first = clickTrack(100, 16, RATE), second = clickTrack(150, 16, RATE)
    const mixed = new Float32Array(first.length)
    for (let index = 0; index < mixed.length; index++) mixed[index] = first[index]! + second[index]!
    const features = extractFeatures(mixed, RATE)
    expect(features.bpmConfidence).toBeLessThan(0.8)
  })

  it('keeps the same tempo across sample rates', () => {
    for (const rate of [8000, 22050, 44100, 48000]) {
      const features = extractFeatures(clickTrack(120, 10, rate), rate)
      expect(features.bpm, `rate=${rate}`).toBeGreaterThanOrEqual(117)
      expect(features.bpm, `rate=${rate}`).toBeLessThanOrEqual(123)
    }
  })

  it('averages channels into mono before measuring', () => {
    const left = new Float32Array([1, 0.5, -1, -0.5])
    const right = new Float32Array([-1, 0.5, 1, 0.5])
    expect(Array.from(mixToMono([left, right]))).toEqual([0, 0.5, 0, 0])
    const mono = mixToMono([clickTrack(120, 10, RATE), clickTrack(120, 10, RATE)])
    const mixed = extractFeatures(mono, RATE)
    expect(mixed.bpm).toBeGreaterThanOrEqual(118)
    expect(mixed.bpm).toBeLessThanOrEqual(122)
  })

  it('caps analysis at 90 seconds but keeps the real duration', () => {
    const features = extractFeatures(clickTrack(120, 120, 8000), 8000)
    expect(features.analyzedSeconds).toBe(90)
    expect(features.duration).toBeCloseTo(120, 0)
    expect(features.energyCurve).toHaveLength(8)
  })

  it('resamples without shifting the tempo', () => {
    const original = clickTrack(120, 10, 44100)
    const resampled = resampleTo(original, 44100, RATE)
    expect(Math.abs(resampled.length - Math.round(original.length * RATE / 44100))).toBeLessThanOrEqual(1)
    const features = extractFeatures(resampled, RATE)
    expect(features.bpm).toBeGreaterThanOrEqual(117)
    expect(features.bpm).toBeLessThanOrEqual(123)
  })

  it('describes band balance and loudness on a bass-heavy track', () => {
    const bass = sine(80, 6, RATE)
    const features = extractFeatures(bass, RATE)
    expect(features.bands.low).toBeGreaterThan(features.bands.high)
    expect(features.brightness).toBeLessThan(0.1)
    expect(features.dynamicRange).toBeLessThan(0.5)
  })
})
