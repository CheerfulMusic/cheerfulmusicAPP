/** UI只依赖播放引擎，后续原生后台播放可以替换实现。 */
export interface PlaybackEngine {
  play(url: string): Promise<void>
  pause(): void
  stop(): void
}

export class WebPlaybackEngine implements PlaybackEngine {
  private audio: HTMLAudioElement | undefined
  async play(url: string): Promise<void> {
    this.audio ??= new Audio()
    if (this.audio.src !== new URL(url, location.href).href) this.audio.src = url
    await this.audio.play()
  }
  pause(): void { this.audio?.pause() }
  stop(): void { if (this.audio) { this.audio.pause(); this.audio.currentTime = 0 } }
}
