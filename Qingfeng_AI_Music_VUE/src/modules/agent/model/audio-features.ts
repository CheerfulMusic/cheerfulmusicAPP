/**
 * 浏览器端音频特征提取（纯函数，不依赖 DOM）。
 *
 * 只做客观测量：响度、动态范围、三频段能量比、谱质心、谱平坦度、能量包络与节拍候选。
 * 不检测调性、和弦或具体乐器——这几项在端侧不可靠，宁可不给。
 * BPM 由节拍显著度自相关估算，带置信度；低于阈值时上层不应展示速度。
 */

export interface AudioFeatures {
  duration: number
  sampleRate: number
  mono: true
  bpm: number
  bpmConfidence: number
  rms: number
  peak: number
  dynamicRange: number
  bands: { low: number; mid: number; high: number }
  brightness: number
  noisiness: number
  energyCurve: number[]
  analyzedSeconds: number
}

export const TARGET_SAMPLE_RATE = 22050
export const MAX_ANALYZED_SECONDS = 90
export const MIN_REPORTED_BPM = 80
export const MAX_REPORTED_BPM = 180
/** 与后端 REFERENCE_ANALYSIS 的阈值保持一致。 */
export const BPM_CONFIDENCE_THRESHOLD = 0.55

const FRAME = 1024
const HOP = 512
const SEARCH_MIN_BPM = 60
const SEARCH_MAX_BPM = 200

export interface ExtractOptions {
  /** 整首时长（秒）；不传则按样本数推算。 */
  duration?: number
  maxSeconds?: number
}

const emptyBands = () => ({ low: 0, mid: 0, high: 0 })

/** 多声道按位平均混成单声道；长度取最长声道，缺失样本按 0 处理。 */
export function mixToMono(channels: Float32Array[]): Float32Array {
  if (channels.length === 0) return new Float32Array(0)
  if (channels.length === 1) return channels[0]!
  const longest = channels.reduce((max, channel) => Math.max(max, channel.length), 0)
  const mono = new Float32Array(longest)
  for (const channel of channels) {
    for (let index = 0; index < channel.length; index++) mono[index]! += channel[index]!
  }
  for (let index = 0; index < longest; index++) mono[index]! /= channels.length
  return mono
}

/** 线性插值重采样；只在需要降采样时调用，保证 90 秒内的计算量可控。 */
export function resampleTo(samples: Float32Array, from: number, to: number): Float32Array {
  if (from === to || samples.length === 0) return samples
  const ratio = to / from
  const length = Math.max(1, Math.floor(samples.length * ratio))
  const output = new Float32Array(length)
  for (let index = 0; index < length; index++) {
    const position = index / ratio
    const left = Math.floor(position)
    const right = Math.min(samples.length - 1, left + 1)
    const weight = position - left
    output[index] = samples[left]! * (1 - weight) + samples[right]! * weight
  }
  return output
}

function hannWindow(size: number): Float64Array {
  const window = new Float64Array(size)
  for (let index = 0; index < size; index++) window[index] = 0.5 - 0.5 * Math.cos((2 * Math.PI * index) / (size - 1))
  return window
}

/** 原地迭代基 2 FFT。帧长固定 1024，不需要通用实现。 */
function fft(re: Float64Array, im: Float64Array): void {
  const size = re.length
  for (let i = 1, j = 0; i < size; i++) {
    let bit = size >> 1
    for (; j & bit; bit >>= 1) j ^= bit
    j ^= bit
    if (i < j) {
      const reTmp = re[i]!, imTmp = im[i]!
      re[i] = re[j]!; im[i] = im[j]!
      re[j] = reTmp; im[j] = imTmp
    }
  }
  for (let length = 2; length <= size; length <<= 1) {
    const angle = (-2 * Math.PI) / length
    const stepRe = Math.cos(angle), stepIm = Math.sin(angle)
    const half = length >> 1
    for (let start = 0; start < size; start += length) {
      let curRe = 1, curIm = 0
      for (let k = 0; k < half; k++) {
        const evenIndex = start + k, oddIndex = evenIndex + half
        const oddRe = re[oddIndex]!, oddIm = im[oddIndex]!
        const vRe = oddRe * curRe - oddIm * curIm
        const vIm = oddRe * curIm + oddIm * curRe
        const uRe = re[evenIndex]!, uIm = im[evenIndex]!
        re[evenIndex] = uRe + vRe; im[evenIndex] = uIm + vIm
        re[oddIndex] = uRe - vRe; im[oddIndex] = uIm - vIm
        const nextRe = curRe * stepRe - curIm * stepIm
        curIm = curRe * stepIm + curIm * stepRe
        curRe = nextRe
      }
    }
  }
}

function percentile(values: number[], ratio: number): number {
  if (values.length === 0) return 0
  const sorted = [...values].sort((left, right) => left - right)
  const index = Math.min(sorted.length - 1, Math.max(0, Math.round((sorted.length - 1) * ratio)))
  return sorted[index]!
}

interface TempoEstimate { bpm: number; confidence: number }

/** 归一化自相关：输入已去均值，输出为 [-1,1] 的相关系数；支持分数滞后（线性插值）。 */
function correlate(envelope: Float64Array, lag: number): number {
  const count = Math.floor(envelope.length - lag - 1)
  if (count <= 8) return 0
  const whole = Math.floor(lag), fraction = lag - whole
  let dot = 0, left = 0, right = 0
  for (let index = 0; index < count; index++) {
    const a = envelope[index]!
    const b = fraction === 0
      ? envelope[index + whole]!
      : envelope[index + whole]! * (1 - fraction) + envelope[index + whole + 1]! * fraction
    dot += a * b; left += a * a; right += b * b
  }
  const norm = Math.sqrt(left * right)
  return norm > 0 ? dot / norm : 0
}

/**
 * 节拍估算：平滑能量包络 + 谐波增强自相关 + 分数滞后 + 细分优先。
 * 三个措施分别解决：帧量化偏差、倍拍骗峰（180 被识别成 90）、半拍丢失（120 被识别成 240）。
 * 只在 80–180 BPM 内给结论；出范围或证据不足时返回 0，由上层判定为「无法判断」。
 */
function estimateTempo(raw: Float64Array, frameRms: number[], framesPerSecond: number): TempoEstimate {
  const none: TempoEstimate = { bpm: 0, confidence: 0 }
  if (raw.length < 12 || frameRms.length === 0) return none

  // 调制深度不足说明没有可辨的强弱起伏：稳态长音、持续铺底不应被当作节拍。
  let meanRms = 0
  for (const value of frameRms) meanRms += value
  meanRms /= frameRms.length
  if (meanRms < 1e-6) return none
  let rmsVariance = 0
  for (const value of frameRms) rmsVariance += (value - meanRms) * (value - meanRms)
  if (Math.sqrt(rmsVariance / frameRms.length) / meanRms < 0.02) return none

  const onset = new Float64Array(raw.length)
  for (let index = 0; index < raw.length; index++) {
    const left = raw[Math.max(0, index - 1)]!, center = raw[index]!, right = raw[Math.min(raw.length - 1, index + 1)]!
    onset[index] = 0.25 * left + 0.5 * center + 0.25 * right
  }
  let mean = 0
  for (const value of onset) mean += value
  mean /= onset.length
  let variance = 0
  const centered = new Float64Array(onset.length)
  for (let index = 0; index < onset.length; index++) {
    const value = onset[index]! - mean
    centered[index] = value
    variance += value * value
  }
  variance /= onset.length
  if (Math.sqrt(variance) < 1e-6) return none

  const searchMin = Math.max(2, (60 * framesPerSecond) / SEARCH_MAX_BPM)
  const searchMax = Math.min(centered.length - 6, (60 * framesPerSecond) / SEARCH_MIN_BPM)
  // 边界放宽 0.6 帧：180 BPM 的真实周期落在 14.36 帧，按整数取整会把峰挡在窗口外。
  const lagMin = Math.max(searchMin, (60 * framesPerSecond) / MAX_REPORTED_BPM - 0.6)
  const lagMax = Math.min(searchMax, (60 * framesPerSecond) / MIN_REPORTED_BPM + 0.6)
  if (lagMax <= lagMin) return none

  const enhanced = (lag: number): number => {
    const first = correlate(centered, lag)
    const second = lag * 2 <= centered.length - 6 ? correlate(centered, lag * 2) : 0
    const third = lag * 3 <= centered.length - 6 ? correlate(centered, lag * 3) : 0
    return (first + 0.5 * second + 0.25 * third) / 1.75
  }

  let best = 0, bestLag = lagMin
  for (let lag = lagMin; lag <= lagMax; lag += 0.25) {
    const score = enhanced(lag)
    if (score > best) { best = score; bestLag = lag }
  }
  if (best <= 0.05) return none

  // 细分优先：若「更快」的假设（1/2、1/3 周期）同样得到支持，说明原峰是倍拍。
  let chosen = bestLag
  for (const divisor of [2, 3]) {
    const faster = bestLag / divisor
    if (faster < lagMin) continue
    if (enhanced(faster) >= best * 0.92) { chosen = faster; break }
  }

  const refined = refineAround(enhanced, chosen)
  const bpm = (60 * framesPerSecond) / refined.lag
  if (bpm < MIN_REPORTED_BPM - 3 || bpm > MAX_REPORTED_BPM + 3) return none
  const clamped = Math.min(MAX_REPORTED_BPM, Math.max(MIN_REPORTED_BPM, bpm))
  return { bpm: Math.round(clamped * 10) / 10, confidence: Math.round(Math.max(0, Math.min(1, refined.score)) * 1000) / 1000 }
}

/** 在候选周期附近做 0.1 帧步长的局部搜索，避免整数格点带来的 BPM 抖动。 */
function refineAround(enhanced: (lag: number) => number, center: number): { lag: number; score: number } {
  let bestLag = center, bestScore = enhanced(center)
  for (let step = -5; step <= 5; step++) {
    const lag = center + step * 0.1
    if (lag < 2) continue
    const score = enhanced(lag)
    if (score > bestScore) { bestScore = score; bestLag = lag }
  }
  return { lag: bestLag, score: bestScore }
}

export function extractFeatures(samples: Float32Array, sampleRate: number, options: ExtractOptions = {}): AudioFeatures {
  const maxSeconds = options.maxSeconds ?? MAX_ANALYZED_SECONDS
  const totalDuration = options.duration ?? (sampleRate > 0 ? samples.length / sampleRate : 0)
  const limit = Math.min(samples.length, Math.max(0, Math.floor(sampleRate * maxSeconds)))
  const analyzedSeconds = sampleRate > 0 ? limit / sampleRate : 0
  const empty: AudioFeatures = {
    duration: totalDuration, sampleRate, mono: true, bpm: 0, bpmConfidence: 0,
    rms: 0, peak: 0, dynamicRange: 0, bands: emptyBands(), brightness: 0, noisiness: 0,
    energyCurve: new Array(8).fill(0), analyzedSeconds,
  }
  if (limit < FRAME * 2) return empty

  let sumSquares = 0, peak = 0
  for (let index = 0; index < limit; index++) {
    const sample = samples[index]!
    sumSquares += sample * sample
    const magnitude = Math.abs(sample)
    if (magnitude > peak) peak = magnitude
  }
  const rms = Math.sqrt(sumSquares / limit)
  if (rms < 1e-6 && peak < 1e-6) return empty

  const frameCount = Math.floor((limit - FRAME) / HOP) + 1
  const window = hannWindow(FRAME)
  const re = new Float64Array(FRAME), im = new Float64Array(FRAME)
  const onset = new Float64Array(frameCount)
  const frameRms: number[] = []
  const bandSums = emptyBands()
  const nyquist = sampleRate / 2
  const lowEdge = 20, lowHigh = 250, midHigh = Math.min(2000, nyquist), highHigh = Math.min(8000, nyquist)
  let centroidSum = 0, centroidWeight = 0, flatnessSum = 0, frames = 0

  for (let frame = 0; frame < frameCount; frame++) {
    const offset = frame * HOP
    let frameEnergy = 0
    for (let index = 0; index < FRAME; index++) {
      const sample = samples[offset + index]!
      frameEnergy += sample * sample
      re[index] = sample * window[index]!
      im[index] = 0
    }
    frameRms.push(Math.sqrt(frameEnergy / FRAME))
    fft(re, im)

    let centroidTop = 0, centroidBottom = 0, logSum = 0, linearSum = 0
    for (let bin = 1; bin < FRAME >> 1; bin++) {
      const magnitude = Math.hypot(re[bin]!, im[bin]!)
      const frequency = (bin * sampleRate) / FRAME
      if (frequency >= lowEdge && frequency < lowHigh) bandSums.low += magnitude * magnitude
      else if (frequency >= lowHigh && frequency < midHigh) bandSums.mid += magnitude * magnitude
      else if (frequency >= midHigh && frequency <= highHigh) bandSums.high += magnitude * magnitude
      centroidTop += frequency * magnitude
      centroidBottom += magnitude
      const power = magnitude * magnitude
      logSum += Math.log(power + 1e-12)
      linearSum += power
    }
    // 用能量包络的正向差分做节拍显著度：对窗相位不敏感，稳态长音不会被误判成鼓点。
    if (frame > 0) onset[frame] = Math.max(0, frameRms[frame]! - frameRms[frame - 1]!)
    const bins = (FRAME >> 1) - 1
    if (centroidBottom > 1e-9) {
      centroidSum += centroidTop / centroidBottom
      centroidWeight += 1
    }
    // 谱平坦度：几何均值/算术均值，白噪接近 1，纯音接近 0。
    flatnessSum += linearSum > 1e-12 ? Math.exp(logSum / bins) / (linearSum / bins) : 0
    frames += 1
  }

  const bandTotal = bandSums.low + bandSums.mid + bandSums.high
  const bands = bandTotal > 0
    ? { low: bandSums.low / bandTotal, mid: bandSums.mid / bandTotal, high: bandSums.high / bandTotal }
    : emptyBands()
  const brightness = centroidWeight > 0 ? Math.max(0, Math.min(1, centroidSum / centroidWeight / nyquist)) : 0
  const noisiness = frames > 0 ? Math.max(0, Math.min(1, flatnessSum / frames)) : 0
  const high = percentile(frameRms, 0.95), low = percentile(frameRms, 0.1)
  const dynamicRange = high > 1e-9 ? Math.max(0, Math.min(1, (high - low) / high)) : 0

  const bucketCount = 8, perBucket = Math.max(1, Math.ceil(frameCount / bucketCount))
  const energyCurve: number[] = []
  for (let bucket = 0; bucket < bucketCount; bucket++) {
    const start = bucket * perBucket, end = Math.min(frameCount, start + perBucket)
    if (start >= end) { energyCurve.push(0); continue }
    let sum = 0
    for (let index = start; index < end; index++) sum += frameRms[index]!
    energyCurve.push(sum / (end - start))
  }
  const loudest = Math.max(...frameRms, 1e-9)
  const normalizedCurve = energyCurve.map(value => Math.round(Math.min(1, value / loudest) * 1000) / 1000)

  const tempo = estimateTempo(onset.subarray(1), frameRms, sampleRate / HOP)
  return {
    duration: totalDuration,
    sampleRate,
    mono: true,
    bpm: tempo.confidence >= BPM_CONFIDENCE_THRESHOLD ? tempo.bpm : 0,
    bpmConfidence: tempo.confidence,
    rms: Math.min(1, rms),
    peak: Math.min(1, peak),
    dynamicRange,
    bands: { low: round3(bands.low), mid: round3(bands.mid), high: round3(bands.high) },
    brightness: round3(brightness),
    noisiness: round3(noisiness),
    energyCurve: normalizedCurve,
    analyzedSeconds: Math.round(analyzedSeconds * 100) / 100,
  }
}

function round3(value: number): number {
  return Math.round(value * 1000) / 1000
}
