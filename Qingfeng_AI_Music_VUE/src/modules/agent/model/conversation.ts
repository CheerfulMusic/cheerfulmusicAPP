/** 对话式 Agent 会话的前端同构契约，与后端 agent/api 下 4 个 record 一一对应。W1 冻结。 */
export type MessageRole = 'user' | 'assistant'
export type MessageKind = 'TEXT' | 'TOOL_CARD' | 'PLACEHOLDER'
/** state 枚举固定为这四种；W2 的 busy 排队写 PLACEHOLDER + PENDING。 */
export type MessageState = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'
export type ToolResultStatus = 'ok' | 'busy' | 'error' | 'clarify'

export interface SongCard {
  songId: string | null
  title: string
  style: string
  lyrics: string
  audioUrl: string
  status: string
  coverUrl: string | null
  revision: number
}

export interface Message {
  id: string
  conversationId: string
  seq: number
  role: MessageRole
  kind: MessageKind
  state: MessageState
  contentJson: string | null
  taskId: string | null
  createdAt: string
}

export interface ConversationState {
  conversationId: string
  currentSongJson: string | null
  revision: number
}

export interface ConversationView {
  id: string
  currentWorkId: string | null
}

export interface ToolResult {
  status: ToolResultStatus
  songCard: SongCard | null
  progress: string | null
  errorCode: string | null
  messageId: string
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)
const isString = (value: unknown): value is string => typeof value === 'string'
const isNullableString = (value: unknown): value is string | null => value === null || isString(value)
const MESSAGE_ROLES: readonly string[] = ['user', 'assistant']
const MESSAGE_KINDS: readonly string[] = ['TEXT', 'TOOL_CARD', 'PLACEHOLDER']
const MESSAGE_STATES: readonly string[] = ['PENDING', 'RUNNING', 'DONE', 'FAILED']
const TOOL_STATUSES: readonly string[] = ['ok', 'busy', 'error', 'clarify']

export function isSongCard(value: unknown): value is SongCard {
  if (!isRecord(value)) return false
  return isNullableString(value.songId)
    && isString(value.title) && isString(value.style) && isString(value.lyrics)
    && isString(value.audioUrl) && isString(value.status)
    && isNullableString(value.coverUrl)
    && typeof value.revision === 'number' && Number.isFinite(value.revision)
}

export function isMessage(value: unknown): value is Message {
  if (!isRecord(value)) return false
  return isString(value.id) && isString(value.conversationId)
    && typeof value.seq === 'number' && Number.isInteger(value.seq)
    && isString(value.role) && MESSAGE_ROLES.includes(value.role)
    && isString(value.kind) && MESSAGE_KINDS.includes(value.kind)
    && isString(value.state) && MESSAGE_STATES.includes(value.state)
    && isNullableString(value.contentJson) && isNullableString(value.taskId)
    && isString(value.createdAt)
}

export function isToolResult(value: unknown): value is ToolResult {
  if (!isRecord(value)) return false
  return isString(value.status) && TOOL_STATUSES.includes(value.status)
    && (value.songCard === null || isSongCard(value.songCard))
    && isNullableString(value.progress) && isNullableString(value.errorCode)
    && isString(value.messageId)
}

/** W1 的离线 stub 契约：后端未启用时前端仍能安全构造一条用户消息。 */
export function stubUserMessage(id: string, conversationId: string, text: string, seq = 1): Message {
  return {
    id, conversationId, seq, role: 'user', kind: 'TEXT', state: 'DONE',
    contentJson: JSON.stringify({ text }), taskId: null,
    createdAt: new Date(0).toISOString(),
  }
}
