import type { Message } from './conversation'

export type AgentCommandKind = 'send' | 'login' | 'recharge' | 'account' | 'privacy' | 'upload' | 'upload_cover' | 'upload_audio'
export interface AgentCommand { label: string; text: string; kind: AgentCommandKind }
export interface AgentResultItem { id: string; title: string; subtitle: string; data: Record<string, unknown> }
export interface AssistantResult { kind: string; items: AgentResultItem[]; commands: AgentCommand[] }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const kinds: AgentCommandKind[] = ['send', 'login', 'recharge', 'account', 'privacy', 'upload', 'upload_cover', 'upload_audio']

/** Render only typed server results. User text and unknown commands never become executable UI. */
export function assistantResult(message: Message): AssistantResult | null {
  if (message.role !== 'assistant' || message.state !== 'DONE') return null
  try {
    const body: unknown = JSON.parse(message.contentJson || '{}')
    if (!object(body)) return null
    const commands = (Array.isArray(body.commands) ? body.commands : []).filter((c): c is AgentCommand =>
      object(c) && typeof c.kind === 'string' && kinds.includes(c.kind as AgentCommandKind)
      && typeof c.label === 'string' && c.label.trim().length > 0 && c.label.length <= 200
      && typeof c.text === 'string' && c.text.length <= 4000 && (c.kind !== 'send' || !!c.text.trim())).slice(0, 50)
    const items = (Array.isArray(body.items) ? body.items : []).filter(object).filter(i =>
      typeof i.id === 'string' && typeof i.title === 'string').slice(0, 100).map(i => ({
        id: i.id as string, title: i.title as string, subtitle: typeof i.subtitle === 'string' ? i.subtitle : '', data: object(i.data) ? i.data : {},
      }))
    return commands.length || items.length ? { kind: typeof body.kind === 'string' ? body.kind : 'result', items, commands } : null
  } catch { return null }
}

/** These result cards only use first-party assets; neither model URLs nor checkout URLs are navigated. */
export function resultMediaUrl(value: unknown, type: 'audio' | 'image', origin: string): string {
  if (typeof value !== 'string') return ''
  try {
    const url = new URL(value, origin)
    if (url.origin !== origin || url.username || url.password || !['http:', 'https:'].includes(url.protocol)) return ''
    const owned = type === 'image' ? /^\/api\/app\/works\/[\w-]+\/covers\/[\w-]+$/ : /^\/api\/app\/works\/[\w-]+\/audio\/[\w-]+$/
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
