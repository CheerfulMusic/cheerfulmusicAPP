(() => {
  const audio = document.getElementById('preview')
  const status = document.getElementById('player-status')
  let counted = false

  audio.addEventListener('playing', () => {
    if (counted || audio.paused) return
    counted = true
    status.hidden = true
    fetch(audio.dataset.playUrl, { method: 'POST', mode: 'same-origin', credentials: 'omit', keepalive: true }).catch(() => {})
  })
  audio.addEventListener('ended', () => { counted = false })
  audio.addEventListener('error', () => { status.hidden = false })
})()
