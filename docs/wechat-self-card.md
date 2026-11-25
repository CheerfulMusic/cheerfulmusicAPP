# 发到我的微信

用户登录青风音乐后，可以从作品分享、公开试听或广场歌曲的分享入口点击「发到我的微信」。首次使用时，在电脑微信中给页面指明的公众号发一条绑定指令。指令十分钟内有效。绑定后，网站向该用户绑定的微信身份发送一条带歌名、封面和公开试听页的图文卡片。

用户在电脑微信打开卡片，再通过微信浏览器的菜单转发给好友或发朋友圈。公众号按用户的有效互动和发送额度开放客服消息。会话过期或额度用尽时，界面引导用户向公众号发送「分享卡片」后再试。

## 开启

1. 在深圳主写服务按发布流程应用 `V58__wechat_self_card.sql`。现有日常本地入口沿用线上数据库配置，不能用它试跑未上线迁移。
2. 复用已有 `qingfeng.wechat.official.app-id` 和 `qingfeng.wechat.official.app-secret`。确认同一个公众号有客服图文消息权限，并在允许的公网域名上配置公开歌曲地址。
3. 配置 `WECHAT_SELF_CARD_ACCOUNT_NAME`、`WECHAT_SELF_CARD_CALLBACK_TOKEN`、`WECHAT_SELF_CARD_ENCODING_AES_KEY`。把 token 和 43 位 EncodingAESKey 写入部署私有配置。启用 `WECHAT_SELF_CARD_ENABLED=true`，在公众号服务器配置中验证 `https://cheerfulmusic.com/api/wechat/card/callback`，选 JSON 格式和安全模式。已有消息回调的公众号需要在原服务中保留既有事件处理，并转交此功能所需的加密消息。
4. 用获准的测试用户确认绑定、收到图文卡片、打开试听，以及电脑微信的好友和朋友圈预览。

把 `WECHAT_SELF_CARD_ENABLED` 或对应的 `qingfeng.wechat.official.self-card.enabled` 属性设为 `false` 即隐藏入口并停止发送，已有绑定可在重新启用后继续使用。若这个回调是公众号唯一的消息接收端，回滚时同时恢复原来的公众号服务器配置。

[微信客服消息规则](https://developers.weixin.qq.com/doc/subscription/guide/product/kf/intro.html) · [发送图文客服消息](https://developers.weixin.qq.com/doc/subscription/api/customer/message/api_sendcustommessage) · [加密回调](https://developers.weixin.qq.com/doc/subscription/guide/dev/push/encryption.html)
