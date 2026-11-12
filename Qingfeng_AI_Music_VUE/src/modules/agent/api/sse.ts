export interface ServerEvent { event: string; data: string }

/** Incremental SSE framing. A partial final event is never treated as a committed result. */
export function createEventParser(onEvent: (event: ServerEvent) => void) {
  let line = '', event = '', data: string[] = [], size = 0, afterCr = false
  const limit = 4 * 1024 * 1024
  function acceptLine() {
    if (!line) {
      if (data.length) onEvent({ event: event || 'message', data: data.join('\n') })
      event = ''; data = []; size = 0
    } else if (!line.startsWith(':')) {
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') event = value
      if (field === 'data') data.push(value)
    }
    line = ''
  }
  return {
    push(chunk: string) {
      for (const character of chunk) {
        if (afterCr) { afterCr = false; if (character === '\n') continue }
        if (++size > limit) throw new Error('Progress event exceeds the size limit')
        if (character === '\r' || character === '\n') {
          acceptLine(); afterCr = character === '\r'
        } else line += character
      }
    },
    finish() {
      if (line || data.length || event) throw new Error('Progress stream ended with an incomplete event')
    },
  }
}

export async function readEventStream(body: ReadableStream<Uint8Array>, signal: AbortSignal, onEvent: (event: ServerEvent) => void) {
  const reader = body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true })
  const parser = createEventParser(onEvent)
  const abort = () => { void reader.cancel().catch(() => undefined) }
  signal.addEventListener('abort', abort, { once: true })
  try {
    if (signal.aborted) return
    while (!signal.aborted) {
      const next = await reader.read()
      if (signal.aborted) return
      if (next.done) { parser.push(decoder.decode()); parser.finish(); return }
      parser.push(decoder.decode(next.value, { stream: true }))
    }
  } finally {
    signal.removeEventListener('abort', abort)
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
