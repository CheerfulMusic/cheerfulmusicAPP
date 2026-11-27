(() => {
  const byId = id => document.getElementById(id)
  const audioPath = byId('audio')?.getAttribute('src') || ''
  const token = /^\/api\/songs\/([A-Za-z0-9_-]{43})\/audio$/.exec(audioPath)?.[1]
  if (!token) return
  const publicBase = '/api/songs/' + token
  const privateBase = '/api/app/song-listening/' + token
  const artistId = document.body.dataset.artist
  const account = byId('account-link'), notice = byId('community-notice')
  const list = byId('comment-list'), artistAudio = byId('artist-audio')
  let state, sort = 'hot', page = 0, readVersion = 0, writing = false, draftKey
  let tracks = [], activeTrack = -1, playingAll = false, artistReady = false
  function element(tag, text, className) {
    const node = document.createElement(tag)
    if (text != null) node.textContent = text
    if (className) node.className = className
    return node
  }
  function action(label, click) {
    const button = element('button', label)
    button.type = 'button'
    button.onclick = click
    return button
  }
  function showNotice(text, login = false) {
    notice.textContent = text
    account.hidden = !login
  }
  function render() {
    for (const [kind, count, selected] of [['like',state.likes,state.liked], ['favorite',state.favorites,state.favorited]]) {
      byId(kind).setAttribute('aria-pressed', String(selected))
      byId(kind + '-count').textContent = count > 0 ? String(count) : ''
    }
    byId('public-story').textContent = state.story || '音乐人还没有公开这首歌的故事。'
    byId('plays').textContent = state.plays + ' 次播放'
    byId('discussion-count').textContent = state.commentCount + ' 条乐评'
    byId('follow').setAttribute('aria-pressed', String(state.following))
    byId('follow').textContent = state.following ? '已关注' : '＋ 关注'
    byId('follow').hidden = !artistId
    if (artistReady) byId('artist-stats').textContent = state.followers + ' 位关注者 · ' + tracks.length + ' 首公开作品'
    list.replaceChildren()
    for (const comment of state.comments) {
      const item = element('li'), author = element('strong', comment.author)
      const date = new Date(comment.createdAt), created = element('time', date.toLocaleDateString('zh-CN'))
      created.dateTime = comment.createdAt
      const url = '/comments/' + encodeURIComponent(comment.id)
      const like = action('赞 ' + comment.likes, () => write(url + '/like', { active: !comment.liked }))
      like.setAttribute('aria-pressed', String(comment.liked))
      item.append(author, created, element('p', comment.text), like)
      if (comment.removable) item.append(action('删除', () => {
        if (window.confirm('删除这条乐评？')) write(url, undefined, 'DELETE', () => { page = 0 })
      }))
      list.append(item)
    }
    if (!list.children.length) list.append(element('li', '还没有乐评，写下第一条听后感吧。', 'empty'))
    byId('comment-pages').hidden = page === 0 && !state.hasMore
    byId('prev-comments').disabled = page === 0
    byId('next-comments').disabled = !state.hasMore
    for (const button of byId('comment-sort').querySelectorAll('button')) button.setAttribute('aria-pressed', String(sort === button.dataset.sort))
  }
  async function refresh(visibleError = false) {
    const turn = ++readVersion
    try {
      const r = await fetch(publicBase + '/community?sort=' + sort + '&page=' + page, { cache: 'no-store' })
      if (turn !== readVersion) return
      if (r.status === 404) {
        byId('audio').pause()
        artistAudio.pause()
        byId('public-story').textContent = ''
        list.replaceChildren()
        state = undefined
        showNotice('这首歌的公开分享已失效。')
        return
      }
      if (!r.ok) throw Error()
      const data = await r.json()
      if (turn !== readVersion) return
      state = data
      render()
    } catch {
      if (turn !== readVersion) return
      if (visibleError) showNotice('音乐故事与互动暂时无法读取。')
    }
  }
  async function write(path, body, method = 'PUT', saved) {
    if (writing) return
    writing = true
    showNotice('')
    try {
      const csrf = await fetch('/api/public/csrf', { cache: 'no-store' })
      if (!csrf.ok) throw Error()
      const headers = { 'X-XSRF-TOKEN': (await csrf.json()).token }
      if (body !== undefined) headers['Content-Type'] = 'application/json'
      const result = await fetch(privateBase + path, { method, headers, body: JSON.stringify(body), credentials: 'same-origin' })
      if (result.status === 401 || result.status === 403) { showNotice('登录后即可参与互动。', true); return }
      if (!result.ok) { showNotice(result.status === 429 ? '操作较频繁，请稍后再试。' : '操作未完成，请刷新后再试。'); return }
      if (saved) saved()
      await refresh(true)
    } catch { showNotice('暂时无法连接，请重试。') }
    finally { writing = false }
  }
  for (const [kind, field] of [['like','liked'],['favorite','favorited'],['follow','following']]) {
    byId(kind).onclick = async () => {
      if (!state) await refresh(true)
      if (state) write('/reactions/' + kind, { active: !state[field] })
    }
  }
  for (const button of byId('comment-sort').querySelectorAll('button')) button.onclick = () => {
    sort = button.dataset.sort
    page = 0
    refresh(true)
  }
  byId('prev-comments').onclick = () => { page = Math.max(0, page - 1); refresh(true) }
  byId('next-comments').onclick = () => { page++; refresh(true) }
  byId('comment-form').onsubmit = event => {
    event.preventDefault()
    const input = byId('comment-text'), text = input.value.trim()
    if (!text) { input.focus(); return }
    if (!draftKey || draftKey.text !== text) {
      const bytes = crypto.getRandomValues(new Uint8Array(16))
      bytes[6] = bytes[6] & 15 | 64
      bytes[8] = bytes[8] & 63 | 128
      const hex = Array.from(bytes, n => n.toString(16).padStart(2,'0')).join('')
      draftKey = { text, id: hex.replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5') }
    }
    write('/comments/' + draftKey.id, { text }, 'PUT', () => {
      if (input.value.trim() === text) input.value = ''
      draftKey = null
      page = 0
      sort = 'new'
      showNotice('乐评已发表。')
    })
  }

  const profileBase = '/api/public/artists/' + encodeURIComponent(artistId || '')
  const assetBase = track => profileBase + '/songs/' + encodeURIComponent(track.id) + '/' + encodeURIComponent(track.version)
  async function playTrack(index, all) {
    const track = tracks[index]
    if (!track) return
    const sameTrack = index === activeTrack
    if (!all && sameTrack && !artistAudio.paused) { artistAudio.pause(); playingAll = false; return }
    byId('audio').pause()
    if (!sameTrack) artistAudio.src = assetBase(track) + '/audio'
    activeTrack = index
    byId('track-prev').disabled = index === 0
    byId('track-next').disabled = index >= tracks.length - 1
    playingAll = all
    byId('artist-listening').textContent = track.title
    byId('artist-playback').hidden = false
    try { await artistAudio.play() }
    catch (error) { if (error.name !== 'AbortError') showNotice('这首公开作品暂时无法播放。') }
  }
  artistAudio.addEventListener('play', () => byId('audio').pause())
  artistAudio.addEventListener('ended', () => { if (playingAll && activeTrack+1 < tracks.length) playTrack(activeTrack+1, true) })
  artistAudio.addEventListener('error', () => showNotice('这首公开作品暂时无法播放。'))
  byId('artist-play-all').onclick = () => playTrack(0, true)
  byId('track-prev').onclick = () => playTrack(activeTrack-1, playingAll)
  byId('track-next').onclick = () => playTrack(activeTrack+1, playingAll)
  async function loadAlbums() {
    const shelf = byId('artist-albums')
    try {
      const response = await fetch(profileBase + '/albums', { cache: 'no-store' })
      if (!response.ok) throw Error()
      const albums = await response.json()
      shelf.replaceChildren()
      for (const album of albums) {
        const card = element('a'), art = element('img')
        card.href = '/artist/' + encodeURIComponent(artistId) + '/albums/' + encodeURIComponent(album.id)
        art.alt = ''
        art.loading = 'lazy'
        const lead = album.tracks.find(track => track.hasCover)
        art.src = album.coverKey ? profileBase + '/albums/' + encodeURIComponent(album.id) + '/cover/' + encodeURIComponent(album.coverKey)
          : lead ? assetBase(lead) + '/cover' : '/brand/share-default-cover.png'
        card.append(art, element('strong',album.title), element('small',album.tracks.length + ' 首歌曲'))
        shelf.append(card)
      }
      if (!albums.length) shelf.append(element('p','音乐人还没有公开专辑。','empty'))
    } catch { shelf.replaceChildren(action('重新载入专辑',loadAlbums)) }
  }
  async function loadArtist() {
    if (!artistId || artistReady) return
    try {
      const response = await fetch(profileBase, { cache: 'no-store' })
      if (!response.ok) throw Error()
      const profile = await response.json()
      tracks = profile.songs
      artistReady = true
      const avatar = byId('artist-avatar')
      if (profile.avatarKey) { avatar.src = profileBase + '/avatar/' + encodeURIComponent(profile.avatarKey); avatar.hidden = false }
      byId('artist-intro').textContent = profile.tagline
      byId('artist-home').href = '/artist/' + encodeURIComponent(artistId)
      byId('artist-home').hidden = false
      byId('artist-play-all').hidden = !tracks.length
      const trackList = byId('artist-tracks')
      trackList.replaceChildren()
      for (const [index,track] of tracks.entries()) {
        const item = element('li'), button = action('', () => playTrack(index, false)), cover = element('img')
        cover.alt = ''
        cover.loading = 'lazy'
        cover.src = track.hasCover ? assetBase(track) + '/cover' : '/brand/share-default-cover.png'
        button.setAttribute('aria-label', '试听 ' + track.title)
        button.append(cover, element('strong', track.title), element('span','▷'))
        item.append(button)
        trackList.append(item)
      }
      if (!tracks.length) trackList.append(element('li', '音乐人还没有发布作品。','empty'))
      if (state) byId('artist-stats').textContent = state.followers + ' 位关注者 · ' + tracks.length + ' 首公开作品'
    } catch { showNotice('音乐人主页暂时无法读取。') }
  }
  document.addEventListener('listening-view', event => {
    showNotice('')
    if (event.detail === 'story') refresh(true)
    if (event.detail === 'artist') { refresh(true); loadArtist(); loadAlbums() }
  })
  if (!byId('artist-panel').hidden) { loadArtist(); loadAlbums() }
  refresh()
})()
