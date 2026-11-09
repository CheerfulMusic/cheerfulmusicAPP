/**
 * 参考音频分析编排：解码 → 混单声道 → 重采样 → Worker 计算 → 缓存。
 *
 * 硬约束：音频字节只在本机内存里流转，不写服务器、不发第三方、不进 sessionStorage。
 * 缓存按文件内容的 SHA-256 索引，只存特征，生命周期为当前页面；同页最多保留 4 条。
 */
import { MAX_ANALYZED_SECONDS, TARGET_SAMPLE_RATE, extractFeatures, mixToMono, resampleTo, type AudioFeatures } from './audio-features'

export type AnalysisFailure = 'UNSUPPORTED' | 'DECODE_FAILED' | 'TIMEOUT' | 'CANCELLED' | 'WORKER_FAILED' | 'TOO_LARGE'

export class AudioAnalysisError extends Error {
  constructor(public code: AnalysisFailure, message: string) { super(message) }
}

export const MAX_REFERENCE_BYTES = 20 * 1024 * 1024
const WORKER_TIMEOUT_MS = 20000
const CACHE_LIMIT = 4

interface DecodedAudio { channels: Float32Array[]; sampleRate: number; duration: number }
interface WorkerLike {
  postMessage: (message: unknown, transfer?: Transferable[]) => void
  terminate: () => void
  onmessage: ((event: MessageEvent) => void) | null
  onerror: ((event: ErrorEvent) => void) | null
}
export interface AnalysisDeps {
  decode?: (file: File) => Promise<DecodedAudio>
  createWorker?: () => WorkerLike | undefined
  digest?: (file: File) => Promise<string | undefined>
  timeoutMs?: number
}

const cache = new Map<string, AudioFeatures>()
let activeJob: WorkerLike | undefined
let activeCancel: (() => void) | undefined
// 代际号：取消或换文件后旧的一次会在下一个检查点以 CANCELLED 结束，不会挂着。
let cancelEpoch = 0

function remember(key: string, features: AudioFeatures) {
  if (cache.has(key)) cache.delete(key)
  cache.set(key, features)
  while (cache.size > CACHE_LIMIT) {
    const oldest = cache.keys().next().value
    if (oldest === undefined) break
    cache.delete(oldest)
  }
}

/** 清掉某一首的缓存；清除参考音乐时调用，避免展示已移除文件的结论。 */
export function forgetAnalysis(key: string | undefined) { if (key) cache.delete(key) }
export function clearAnalysisCache() { cache.clear() }

/** 取消正在跑的分析（页面卸载、切换文件时用）；被取消的那次会以 CANCELLED 结束，不会永久挂起。 */
export function cancelAnalysis() {
  cancelEpoch += 1
  const cancel = activeCancel
  activeCancel = undefined
  if (activeJob) { activeJob.terminate(); activeJob = undefined }
  cancel?.()
}

function cancelled(): AudioAnalysisError {
  return new AudioAnalysisError('CANCELLED', '分析已被新的文件替换。')
}

async function defaultDigest(file: File): Promise<string | undefined> {
  const subtle = globalThis.crypto?.subtle
  if (!subtle) return undefined
  try {
    const hash = await subtle.digest('SHA-256', await file.arrayBuffer())
    return Array.from(new Uint8Array(hash)).map(byte => byte.toString(16).padStart(2, '0')).join('')
  } catch { return undefined }
}

async function defaultDecode(file: File): Promise<DecodedAudio> {
  const constructors = window as unknown as { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext }
  const Constructor = constructors.AudioContext || constructors.webkitAudioContext
  if (!Constructor) throw new AudioAnalysisError('UNSUPPORTED', '当前浏览器不支持音频分析。')
  const context = new Constructor()
  try {
    const buffer = await context.decodeAudioData(await file.arrayBuffer())
    const channels: Float32Array[] = []
    for (let index = 0; index < buffer.numberOfChannels; index++) channels.push(buffer.getChannelData(index))
    return { channels, sampleRate: buffer.sampleRate, duration: buffer.duration }
  } catch (error) {
    if (error instanceof AudioAnalysisError) throw error
    throw new AudioAnalysisError('DECODE_FAILED', '这段音频无法解码，已保留文件名与时长。')
  } finally {
    void context.close().catch(() => undefined)
  }
}

function defaultWorker(): WorkerLike | undefined {
  if (typeof Worker === 'undefined') return undefined
  try {
    return new Worker(new URL('./audio-features.worker.ts', import.meta.url), { type: 'module' }) as unknown as WorkerLike
  } catch { return undefined }
}

function runInWorker(worker: WorkerLike, audio: DecodedAudio, timeoutMs: number): Promise<AudioFeatures> {
  return new Promise<AudioFeatures>((resolve, reject) => {
    let settled = false
    const finish = (error?: AudioAnalysisError, features?: AudioFeatures) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      worker.terminate()
      if (activeJob === worker) activeJob = undefined
      if (activeCancel === cancel) activeCancel = undefined
      if (error) reject(error)
      else resolve(features!)
    }
    const cancel = () => finish(new AudioAnalysisError('CANCELLED', '分析已被新的文件替换。'))
    const timer = setTimeout(() => finish(new AudioAnalysisError('TIMEOUT', '音频分析超时，已按文件信息继续。')), timeoutMs)
    worker.onmessage = event => {
      const payload = event.data as { ok?: boolean; features?: AudioFeatures; message?: string }
      if (payload?.ok && payload.features) finish(undefined, payload.features)
      else finish(new AudioAnalysisError('WORKER_FAILED', payload?.message || '音频分析失败，已按文件信息继续。'))
    }
    worker.onerror = () => finish(new AudioAnalysisError('WORKER_FAILED', '音频分析线程异常，已按文件信息继续。'))
    activeCancel = cancel
    const channels = audio.channels.map(channel => channel)
    worker.postMessage({ channels, sampleRate: audio.sampleRate, duration: audio.duration },
      channels.map(channel => channel.buffer as ArrayBuffer))
  })
}

/** Worker 不可用时的兜底：让出一帧再算，避免在点击瞬间同步阻塞绘制。 */
async function runOnMainThread(audio: DecodedAudio): Promise<AudioFeatures> {
  await new Promise(resolve => setTimeout(resolve, 0))
  const mono = mixToMono(audio.channels)
  const data = audio.sampleRate > TARGET_SAMPLE_RATE ? resampleTo(mono, audio.sampleRate, TARGET_SAMPLE_RATE) : mono
  return extractFeatures(data, Math.min(audio.sampleRate, TARGET_SAMPLE_RATE), { duration: audio.duration, maxSeconds: MAX_ANALYZED_SECONDS })
}

export interface AnalysisResult { features: AudioFeatures; key?: string }

export async function analyzeAudioFile(file: File, deps: AnalysisDeps = {}): Promise<AnalysisResult> {
  if (file.size > MAX_REFERENCE_BYTES) throw new AudioAnalysisError('TOO_LARGE', '参考音乐不能超过 20MB。')
  const startEpoch = cancelEpoch
  const digest = deps.digest ?? defaultDigest
  const key = await digest(file)
  if (startEpoch !== cancelEpoch) throw cancelled()
  if (key && cache.has(key)) return { features: cache.get(key)!, key }

  cancelAnalysis()
  const epoch = cancelEpoch
  let audio: DecodedAudio
  try {
    audio = await (deps.decode ?? defaultDecode)(file)
  } catch (error) {
    if (error instanceof AudioAnalysisError) throw error
    throw new AudioAnalysisError('DECODE_FAILED', '这段音频无法解码，已保留文件名与时长。')
  }
  if (epoch !== cancelEpoch) throw cancelled()
  if (!audio.channels.length) throw new AudioAnalysisError('DECODE_FAILED', '这段音频无法解码，已保留文件名与时长。')

  const createWorker = deps.createWorker ?? defaultWorker
  const worker = createWorker()
  let features: AudioFeatures
  if (worker) {
    activeJob = worker
    features = await runInWorker(worker, audio, deps.timeoutMs ?? WORKER_TIMEOUT_MS)
  } else {
    features = await runOnMainThread(audio)
  }
  if (key) remember(key, features)
  return { features, key }
}
