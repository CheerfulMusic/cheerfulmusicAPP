(() => {
  const byId = id => document.getElementById(id)
  const audio = byId('audio'), seek = byId('seek'), play = byId('play')
  if (!audio || !seek || !play) return
  const section = byId('lyrics'), lines = byId('lyric-lines')
  const timedLines = Array.from(lines.querySelectorAll('[data-start][data-end]'))
  const firstLine = lines.querySelector('p:not(.empty-lyrics)')
  if (firstLine) {
    byId('lyric-peek').textContent = firstLine.textContent
    byId('lyric-preview').hidden = false
    byId('lyric-preview').onclick = () => byId('lyrics-toggle').click()
  }
  let activeLine, browsingUntil = 0, dragging = false
  const time = seconds => Math.floor(seconds / 60) + ':' + String(Math.floor(seconds % 60)).padStart(2, '0')
  const finiteDuration = value => Number.isFinite(value) && value > 0
  function updateTime() {
    const realDuration = audio.duration
    const duration = finiteDuration(realDuration) ? realDuration : Number(audio.dataset.duration)
    byId('duration').textContent = finiteDuration(duration) ? time(duration) : '–:––'
    seek.disabled = !finiteDuration(realDuration)
    if (finiteDuration(duration)) seek.max = String(duration)
    const now = dragging ? Number(seek.value) : audio.currentTime || 0
    if (!dragging) seek.value = String(now)
    byId('elapsed').textContent = time(now)
    seek.style.setProperty('--played', (finiteDuration(duration) ? Math.min(100, 100 * now / duration) : 0) + '%')
    seek.setAttribute('aria-valuetext', time(now))
    if (!timedLines.length) return
    const next = timedLines.find(line => now >= Number(line.dataset.start) && now < Number(line.dataset.end))
    if (activeLine === next) return
    if (activeLine) activeLine.classList.remove('current')
    activeLine = next
    if (!next) return
    next.classList.add('current')
    byId('lyric-peek').textContent = next.textContent
    if (!section.hidden && Date.now() >= browsingUntil) {
      const offset = next.getBoundingClientRect().top - lines.getBoundingClientRect().top
      if (offset < 0 || offset + next.offsetHeight > lines.clientHeight) lines.scrollTop += offset - lines.clientHeight / 3
    }
  }
  for (const name of ['loadedmetadata', 'durationchange', 'timeupdate', 'seeked']) audio.addEventListener(name, updateTime)
  for (const name of ['wheel', 'touchstart', 'pointerdown', 'keydown']) {
    lines.addEventListener(name, () => { browsingUntil = Date.now() + 5000 }, { passive: true })
  }
  seek.addEventListener('input', () => { dragging = true; updateTime() })
  seek.addEventListener('change', () => {
    if (finiteDuration(audio.duration)) audio.currentTime = Math.min(Number(seek.value), audio.duration)
    dragging = false
    updateTime()
  })
  function playbackState() {
    const playing = !audio.paused && !audio.ended
    for (const button of [play, byId('mini-play')]) {
      button.dataset.playing = String(playing)
      button.setAttribute('aria-label', playing ? '暂停' : '播放')
    }
  }
  for (const name of ['play', 'pause', 'ended']) audio.addEventListener(name, playbackState)
  audio.addEventListener('playing', () => { byId('audio-status').textContent = '' })
  audio.addEventListener('error', () => { byId('audio-status').textContent = '音频暂时无法播放，请刷新后再试。'; playbackState() })
  play.onclick = async () => {
    if (!audio.paused && !audio.ended) { audio.pause(); return }
    byId('artist-audio').pause()
    try { await audio.play() }
    catch (error) { if (error.name !== 'AbortError') byId('audio-status').textContent = '暂时无法播放，请再试一次。' }
  }
  byId('mini-play').onclick = () => play.click()
  byId('repeat').onclick = event => {
    audio.loop = !audio.loop
    event.currentTarget.setAttribute('aria-pressed', String(audio.loop))
  }
  const panels = { lyrics: section, story: byId('story'), artist: byId('artist-panel') }
  const panelButtons = { lyrics: byId('lyrics-toggle'), story: byId('open-story'), artist: byId('open-artist') }
  let currentView = null
  function showPanel(name, focus) {
    const previous = currentView
    currentView = name
    for (const [key, panel] of Object.entries(panels)) panel.hidden = name !== key
    byId('mini-player').hidden = !name
    byId('record').hidden = !!name
    byId('lyrics-toggle').setAttribute('aria-expanded', String(name === 'lyrics'))
    document.body.classList.toggle('in-panel', !!name)
    if (name !== 'artist') byId('artist-audio').pause()
    if (name === 'lyrics' && activeLine) lines.scrollTop += activeLine.getBoundingClientRect().top - lines.getBoundingClientRect().top
    if (focus) {
      window.scrollTo(0, 0)
      const target = name ? panels[name].querySelector('h2') : panelButtons[previous]
      if (target) target.focus({ preventScroll: true })
    }
    document.dispatchEvent(new CustomEvent('listening-view', { detail: name }))
  }
  for (const [name, button] of Object.entries(panelButtons)) {
    button.onclick = () => {
      if (currentView === name) return
      history.pushState({ listeningView: name }, '')
      showPanel(name, true)
    }
  }
  byId('open-artist').hidden = !document.body.dataset.artist
  byId('back-record').onclick = () => { if (history.state?.listeningView) history.back(); else showPanel(null, true) }
  window.addEventListener('popstate', event => showPanel(panels[event.state?.listeningView] ? event.state.listeningView : null, true))
  showPanel(panels[history.state?.listeningView] ? history.state.listeningView : null, false)
  updateTime()
  playbackState()
  audio.hidden = true
  byId('transport').hidden = false
  document.body.classList.add('enhanced')

  const sheet = byId('share-sheet'), status = byId('status')
  const posterImage = byId('poster-image'), preview = byId('poster-preview')
  const trigger = byId('open-share'), close = byId('close-share')
  let posterPromise, prepared, generation = 0
  const canSend = file => {
    try { return !!(file && navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) }
    catch { return false }
  }
  function preparePoster() {
    if (posterPromise) return posterPromise
    const turn = generation
    posterPromise = import('/brand/song-poster.js').then(async renderer => {
      const title = document.querySelector('h1').textContent
      const artwork = byId('artwork')
      const lyrics = Array.from(lines.querySelectorAll('p:not(.empty-lyrics)'), line => line.textContent).join('\n')
      const blob = await renderer.renderSongPoster({
        title, cover: artwork.currentSrc || artwork.src, signature: byId('artist').textContent,
        link: document.querySelector('meta[property="og:url"]').content,
        quote: renderer.lyricQuotes(lyrics)[0] || '', tagline: '扫码收听这首歌', scanLabel: '', expiresLabel: '',
      })
      if (turn !== generation) return
      const name = renderer.posterFilename(title)
      const file = typeof File === 'function' ? new File([blob], name, { type: blob.type }) : null
      const imageUrl = await new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result)
        reader.onerror = () => reject(new Error('海报图片读取失败'))
        reader.readAsDataURL(blob)
      })
      if (turn !== generation) return
      prepared = { file, name, imageUrl }
      return prepared
    }).catch(error => {
      if (turn === generation) posterPromise = undefined
      throw error
    })
    return posterPromise
  }
  function reveal(result) {
    preview.hidden = false
    posterImage.src = result.imageUrl
    const save = byId('save-poster')
    save.href = result.imageUrl
    save.download = result.name
    byId('send-poster').hidden = !canSend(result.file)
    status.textContent = '可保存海报，也可以在手机上长按图片。'
  }
  async function sendImage(result) {
    try { await navigator.share({ files: [result.file] }) }
    catch (error) { if (sheet.hasAttribute('open') && error.name !== 'AbortError') status.textContent = '可保存下方海报，再发送给朋友。' }
  }
  byId('poster').onclick = async () => {
    if (prepared) {
      reveal(prepared)
      if (canSend(prepared.file)) await sendImage(prepared)
      return
    }
    const turn = generation
    status.textContent = '正在生成海报…'
    try {
      const result = await preparePoster()
      if (turn === generation && result) reveal(result)
    } catch { if (turn === generation) status.textContent = '海报暂时无法生成，可重试或复制链接。' }
  }
  byId('send-poster').onclick = () => { if (prepared && canSend(prepared.file)) return sendImage(prepared) }
  function dismiss() {
    generation++
    posterPromise = undefined
    prepared = undefined
    preview.hidden = true
    posterImage.removeAttribute('src')
    byId('save-poster').removeAttribute('href')
    byId('manual').hidden = true
    status.textContent = ''
    if (typeof sheet.close === 'function' && sheet.open) sheet.close()
    else sheet.removeAttribute('open')
    document.body.classList.remove('sharing', 'sheet-fallback')
    trigger.focus({ preventScroll: true })
  }
  trigger.onclick = () => {
    if (sheet.hasAttribute('open')) return
    if (typeof sheet.showModal === 'function') sheet.showModal()
    else { sheet.setAttribute('open', ''); document.body.classList.add('sheet-fallback') }
    document.body.classList.add('sharing')
    close.focus({ preventScroll: true })
    preparePoster().catch(() => {})
  }
  close.onclick = dismiss
  sheet.addEventListener('cancel', event => { event.preventDefault(); dismiss() })
  sheet.addEventListener('click', event => {
    if (event.target !== sheet) return
    const box = sheet.getBoundingClientRect()
    if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) dismiss()
  })
  sheet.addEventListener('keydown', event => {
    if (!document.body.classList.contains('sheet-fallback')) return
    if (event.key === 'Escape') { event.preventDefault(); dismiss(); return }
    if (event.key !== 'Tab') return
    const controls = Array.from(sheet.querySelectorAll('button, a[href], input')).filter(el => !el.closest('[hidden]') && !el.disabled)
    const first = controls[0], last = controls[controls.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  })
})()
