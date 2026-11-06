# 新版阿里云国际短信 SenderID 适配

2026-11-06 已发布应用适配，源码 `db23772f44c8891ac95c18e658044c92757ac3b7`。Logto 后台仍待用户更换 HTTP SMS 连接器并完成真实收码验证；网站海外发码入口继续关闭。

## 原因与行为

用户已开通的阿里云新版国际短信采用 SenderID 与完整 Message，不再使用旧的 SignName/TemplateCode。Logto 标准 Aliyun SMS 连接器仍调用旧 SendSms；先前建议在该连接器填写国际签名/模板不适用于此次新版服务，已纠正。

新增服务器 HTTP 短信适配 `POST /api/public/auth/sms/deliver`，接收 Logto 原生 `{to,type,payload:{code}}`。Logto 继续生成和验证验证码，本接口仅发送，不独立签发登录身份。

- 海外调用固定 Singapore endpoint 的 SendMessageToGlobe（2018-05-01），SenderID 为用户设置的 Cheerful，Type=OTP，固定英文验证码正文；ChannelId 可选，不能使用 UK_SMS 等显示名称代替数字 ID。
- 中国大陆保留现有 MAS SendSmsVerifyCode（2017-05-25），直接发送同一个 Logto 验证码，避免重新生成另一验证码。
- 高熵 Bearer 鉴权先于请求体解析；仅该精确 POST 地址免内测门禁与 CSRF，其他登录接口保持原有保护。
- 请求体上限 2KB，手机号/六位验证码/用途校验；单号码一分钟一次、每小时五次，全站默认每小时200次；进程内十分钟重复请求去重，并发或模糊失败不自动重发。
- SDK 不自动重试，验证码只放 POST body；不记录号码、验证码、密钥、原始响应或异常 cause，诊断只保留固定服务名及白名单错误码。

## 配置与待办

Logto 应更换为 HTTP SMS，填写：

```json
{
  "endpoint": "https://cheerfulmusic.com/api/public/auth/sms/deliver",
  "authorization": "Bearer <独立随机密钥>"
}
```

真实授权值已生成到本机 Git 忽略且仅本用户/SYSTEM 可读的私有文件，未写入本记录或 Git。阿里云凭据由既有私有 MAS 配置安全复用到服务器，国际短信调用权限及目标地区送达尚待真实测试确认。

后台适配已启用以便用户测试，但 `qingfeng.auth.logto.phone-international-enabled=false` 保持关闭。当前保存的 Logto MAS 连接器尚未更改；只有用户完成 HTTP SMS 测试并保存后，才继续开放海外发码。

## 验证与发布

执行 fetch 并合并 main/dev/yth（均以8be56e1为基线）。前端188项、类型与生产构建通过；后端252项verify通过。新增14项覆盖 SDK 实际HTTP请求签名/路由/参数、验证码不进URL、禁止重试、Bearer与门禁/CSRF边界、非法输入、发送限额、并发去重和失败脱敏；独立代码审查通过。

公网48项无发送检查通过，含新接口无/错Bearer拒绝、正确Bearer无Cookie/CSRF可达但拒绝非法输入、2KB限制，及原登录能力、页面资源与账号回归。所有公网探针均在调用短信服务之前拒绝，没有发送真实短信，也未验证真实送达。

- 新版本：`/opt/qingfeng-music/releases/20261106-155253-sms-bridge`
- 上一版：`/opt/qingfeng-music/releases/20261106-1456-8be56e1`（发布前校验发现另一任务已上线该版本，已据此重新建立基线，没有覆盖旧快照）
- JAR SHA256：`72becbfef12f6b25f230c0c2d792556655c565b284148892ade2a77dca134b74`
- 停服备份：`/var/backups/qingfeng-music/20261106T075413Z`（北京时间15:54:13）。数据库、媒体/配置及部署配置归档校验通过。
- 配置新增12个server-only bridge参数，并显式增加原本默认关闭的海外入口false；其余应用配置值与额度/邀请码配置哈希一致。私有配置失败时可随应用回滚恢复。
- 发布前后123作品、98媒体、108任务、96创建者、1个Google身份、3个OIDC身份数量一致；账本与订单仍为0，公网验收另建1个匿名账号并退出。
- Flyway仍V20，无迁移；应用健康UP，应用/Nginx/MySQL均active，独立终验确认备份有效且启动日志无错误。

需要回滚时，恢复备份中的私有应用配置，将current原子切回上一版并重启。Logto目前未改变；若后续已切换HTTP连接器，回滚前需同时恢复旧MAS连接器，或保留可用的短信适配服务，避免连接器指向已移除的接口。不要覆盖后续用户数据库活动。

官方依据：[新版SenderID接口](https://help.aliyun.com/zh/sms/getting-started/use-sms-api/)、[Logto HTTP SMS](https://docs.logto.io/integrations/http-sms)。配置字段见 `Qingfeng_AI_Music/deploy/sms-bridge.properties.example`；机器证据见 [evidence.json](evidence.json)。
