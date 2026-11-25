// Loaded before the creator and public listening pages so sharing starts in the user's click.
(() => {
  const clientUrl = 'weixin://'
  const wechat = /MicroMessenger|WindowsWechat|MacWechat|\bWeChat\//i.test(navigator.userAgent)
  const isWindows = /Windows NT|WindowsWechat/i.test(navigator.userAgent)
  const windowsInstruction = '把地址发到电脑微信的「文件传输助手」并点开，再从微信浏览器的「…」菜单发送网页卡片。'

  function mode(destination) {
    if (wechat || !['friend', 'timeline'].includes(destination)) return ''
    return 'client'
  }

  function draft(data, destination) {
    if (isWindows && destination) {
      const entry = new URL(data.url)
      entry.hash = 'share-' + destination
      return entry.href
    }
    return [data.title, data.url].filter(Boolean).join('\n')
  }

  async function copyDraft(data, destination) {
    try {
      await navigator.clipboard.writeText(draft(data, destination))
      if (isWindows) return { clipboard: 'copied', message: '地址已复制，可在电脑微信中打开歌曲页。' }
      const message = destination === 'timeline'
        ? '歌曲和链接已复制。可粘贴到朋友圈，要分享歌曲卡片请在微信内打开歌曲并使用右上角菜单。'
        : '歌曲和链接已复制。到微信选择好友，粘贴后发送。'
      return { clipboard: 'copied', message }
    } catch {
      return { clipboard: 'manual', message: isWindows ? '请手动复制歌曲页地址。' : '请手动复制下方歌曲和链接。' }
    }
  }

  async function open(destination, data) {
    if (!mode(destination)) return
    // On Windows, copying the address to open in WeChat is a separate action.
    // Outside Windows, do not await the clipboard before opening the app.
    const copying = isWindows ? undefined : copyDraft(data, destination)
    try {
      window.location.assign(clientUrl)
    } catch {
      if (!copying) return { message: '浏览器未能打开微信。请手动打开电脑微信。' }
      const result = await copying
      return { ...result, message: result.clipboard === 'copied'
        ? '浏览器未能打开微信。歌曲和链接已复制，可手动打开微信粘贴。'
        : '浏览器未能打开微信。请复制下方内容，再手动打开微信。' }
    }
    return copying
  }

  window.cheerfulWechat = { mode, draft, copyDraft, open, clientUrl, isWindows, windowsInstruction }
})()
