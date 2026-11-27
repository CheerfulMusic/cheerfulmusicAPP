// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { expect, it, vi } from 'vitest'

const script = readFileSync('public/brand/x-player.js', 'utf8')

it('counts actual playback once per session and shows the normal listening page as a fallback', () => {
  document.body.innerHTML = `
    <audio id="preview" controls preload="none" data-play-url="/api/songs/test/play"></audio>
    <p id="player-status" role="status" hidden>Open the song page to try again.</p>
    <a href="/song/test" target="_blank">Open song page</a>`
  const audio = document.querySelector<HTMLAudioElement>('audio')!
  const status = document.getElementById('player-status')!
  const fetch = vi.fn().mockResolvedValue(undefined)
  let paused = true
  Object.defineProperty(audio, 'paused', { get: () => paused })
  runInNewContext(script, { document, fetch })
  const fire = (type: string) => audio.dispatchEvent(new Event(type))

  expect(fetch).not.toHaveBeenCalled()
  fire('loadedmetadata')
  fire('play')
  fire('playing')
  expect(fetch).not.toHaveBeenCalled()

  paused = false
  fire('playing')
  fire('pause')
  fire('playing')
  expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/songs/test/play', {
    method: 'POST', mode: 'same-origin', credentials: 'omit', keepalive: true,
  })

  fire('ended')
  fire('playing')
  expect(fetch).toHaveBeenCalledTimes(2)
  fire('error')
  expect(status.hidden).toBe(false)
  expect(document.querySelector('a')?.getAttribute('href')).toBe('/song/test')
})
