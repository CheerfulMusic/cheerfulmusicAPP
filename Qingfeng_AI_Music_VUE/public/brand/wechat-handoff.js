// Loaded before the creator and public listening pages so sharing starts in the user's click.
(() => {
  const clientUrl = 'weixin://'

  function mode(destination) {
    if (/MicroMessenger/i.test(navigator.userAgent) || !['friend', 'timeline'].includes(destination)) return ''
    return 'client'
  }

  function draft(data) {
    return [data.title, data.url].filter(Boolean).join('\n')
  }

  async function copyDraft(data, destination) {
    try {
      await navigator.clipboard.writeText(draft(data))
      const message = destination === 'timeline'
        ? '歌曲和链接已复制。可粘贴到朋友圈，要分享歌曲卡片请在微信内打开歌曲并使用右上角菜单。'
        : '歌曲和链接已复制。到微信选择好友，粘贴后发送。'
      return { clipboard: 'copied', message }
    } catch {
      return { clipboard: 'manual', message: '请手动复制下方歌曲和链接。' }
    }
  }

  async function open(destination, data) {
    if (!mode(destination)) return
    // Both operations need the original click. Waiting for the clipboard may lose user activation.
    const copying = copyDraft(data, destination)
    try {
      window.location.assign(clientUrl)
    } catch {
      const result = await copying
      return { ...result, message: result.clipboard === 'copied'
        ? '浏览器未能打开微信。歌曲和链接已复制，可手动打开微信粘贴。'
        : '浏览器未能打开微信。请复制下方内容，再手动打开微信。' }
    }
    return copying
  }

  window.cheerfulWechat = { mode, draft, copyDraft, open, clientUrl }
})()
