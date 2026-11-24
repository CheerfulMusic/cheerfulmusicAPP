import type { DisplayedContext, Message } from './conversation'

export type AgentCommandKind = 'send' | 'login' | 'recharge' | 'account' | 'privacy' | 'photo' | 'upload' | 'upload_cover' | 'upload_audio' | 'feature'
export interface AgentCommand { label: string; text: string; kind: AgentCommandKind }
export interface AgentResultItem { id: string; title: string; subtitle: string; data: Record<string, unknown> }
export interface AssistantResult { kind: string; items: AgentResultItem[]; commands: AgentCommand[] }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const kinds: AgentCommandKind[] = ['send', 'login', 'recharge', 'account', 'privacy', 'photo', 'upload', 'upload_cover', 'upload_audio', 'feature']
const uuid = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i

export function displayedWorkList(messages: Message[]): DisplayedContext['workList'] {
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i]!
    if (message.role !== 'assistant' || message.kind !== 'TEXT' || message.state !== 'DONE') continue
    try {
      const body: unknown = JSON.parse(message.contentJson || '{}')
      if (!object(body) || !Array.isArray(body.items)) continue
      if (body.kind === 'works' || body.kind === 'memory') return { messageId: message.id, seq: message.seq }
      if (body.items.length) return undefined
    } catch { /* Only persisted structured results can identify a list. */ }
  }
}

// Older semantic cards stored the matched revision on the item and a work-only open command.
function versionedMemoryCommands(commands: AgentCommand[], items: AgentResultItem[]): AgentCommand[] {
  return commands.flatMap(command => {
    if (command.kind !== 'send') return [command]
    const item = items.find(row => command.text === `打开作品 ${row.id}`)
    if (!item || !item.data.revisionId) return [command]
    const revision = item.data.revisionId
    if (item.data.workId !== item.id || !uuid.test(item.id) || typeof revision !== 'string' || !uuid.test(revision)) return []
    return [{ ...command, text: `打开作品 ${item.id} 的歌词版本 ${revision}` }]
  })
}

/** Render only typed server results. User text and unknown commands never become executable UI. */
export function assistantResult(message: Message): AssistantResult | null {
  if (message.role !== 'assistant' || message.state !== 'DONE') return null
  try {
    const body: unknown = JSON.parse(message.contentJson || '{}')
    if (!object(body) || body.kind === 'progress') return null
    const commands = (Array.isArray(body.commands) ? body.commands : []).filter((c): c is AgentCommand =>
      object(c) && typeof c.kind === 'string' && kinds.includes(c.kind as AgentCommandKind)
      && typeof c.label === 'string' && c.label.trim().length > 0 && c.label.length <= 200
      && typeof c.text === 'string' && c.text.length <= 4000 && (c.kind !== 'send' || !!c.text.trim())).slice(0, 50)
    const items = (Array.isArray(body.items) ? body.items : []).filter(object).filter(i =>
      typeof i.id === 'string' && typeof i.title === 'string').slice(0, body.kind === 'capabilities' ? 200 : 100).map(i => ({
        id: i.id as string, title: i.title as string, subtitle: typeof i.subtitle === 'string' ? i.subtitle : '', data: object(i.data) ? i.data : {},
      }))
    return commands.length || items.length ? {
      kind: typeof body.kind === 'string' ? body.kind : 'result', items,
      commands: body.kind === 'memory' ? versionedMemoryCommands(commands, items) : commands,
    } : null
  } catch { return null }
}

/** These result cards only use first-party assets; neither model URLs nor checkout URLs are navigated. */
export function resultMediaUrl(value: unknown, type: 'audio' | 'image' | 'video', origin: string): string {
  if (typeof value !== 'string') return ''
  try {
    const url = new URL(value, origin)
    if (url.origin !== origin || url.username || url.password || !['http:', 'https:'].includes(url.protocol)) return ''
    const owned = type === 'video' ? /^\/api\/app\/music-videos\/[a-fA-F0-9-]{36}\/content$/ : type === 'image' ? /^\/api\/app\/works\/[\w-]+\/covers\/[\w-]+$/ : /^\/api\/app\/works\/[\w-]+\/audio\/[\w-]+$/
    const sample = type === 'audio' && /^\/media\/[\w.-]+\.(mp3|wav)$/i.test(url.pathname)
    return owned.test(url.pathname) || sample ? url.href : ''
  } catch { return '' }
}

export function uploadTarget(command: AgentCommand): { kind: 'upload_cover' | 'upload_audio'; workId: string } | null {
  let kind = command.kind, workId = command.text
  if (kind === 'upload') {
    const match = /^(cover|audio):([\w-]+)$/.exec(workId)
    if (!match) return null
    kind = match[1] === 'cover' ? 'upload_cover' : 'upload_audio'; workId = match[2]!
  }
  return (kind === 'upload_cover' || kind === 'upload_audio') && /^[\w-]{1,100}$/.test(workId) ? { kind, workId } : null
}

export function resultInviteUrl(value: unknown, origin: string): string {
  return typeof value === 'string' && /^\/account\?invite=[A-Za-z0-9_-]{6,128}$/.test(value)
    ? new URL(value, origin).href : ''
}

export interface AgentFeatureTarget { feature: 'artist' | 'discover' | 'analytics' | 'work' | 'avatar' | 'reference' | 'mv' | 'mv_image' | 'payment_risk' | 'editorial' | 'gift'; giftToken?: string; workId?: string; assetId?: string; title?: string }
export function featureTarget(command: AgentCommand): AgentFeatureTarget | null {
  if (command.kind !== 'feature') return null
  try {
    const v: unknown = JSON.parse(command.text)
    if (!object(v) || !['artist', 'discover', 'analytics', 'work', 'avatar', 'reference', 'mv', 'mv_image', 'payment_risk', 'editorial', 'gift'].includes(String(v.feature))) return null
    const id = (x: unknown) => typeof x === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(x)
    if (['work', 'mv', 'mv_image'].includes(String(v.feature)) && !id(v.workId)) return null
    if (['mv', 'mv_image'].includes(String(v.feature)) && !id(v.assetId)) return null
    if (v.feature === 'gift' && (typeof v.giftToken !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(v.giftToken))) return null
    return { giftToken: v.feature === 'gift' ? v.giftToken as string : undefined, feature: v.feature as AgentFeatureTarget['feature'], workId: id(v.workId) ? v.workId as string : undefined, assetId: id(v.assetId) ? v.assetId as string : undefined, title: typeof v.title === 'string' ? v.title.slice(0, 100) : undefined }
  } catch { return null }
}
export function sharedDownloadUrl(value: unknown): string {
  return typeof value === 'string' && /^\/api\/app\/shared-downloads\/[a-fA-F0-9-]{36}$/.test(value) ? value : ''
}
