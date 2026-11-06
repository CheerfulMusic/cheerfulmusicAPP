# 企业国际短信凭据预置

2026-11-06 北京时间 18:10 完成服务器私有配置预置。用户明确要求先使用企业主账号 AccessKey，并提供企业 SenderID `CheerMusic`。本次没有发送短信，国际登录尚未开放。

## 当前结果

- 企业主账号身份已由 STS 确认，账号与用户指定的企业账号一致。主账号凭据未写入 Git、日志、命令行参数或本记录。
- 国际短信的两项凭据和 SenderID 已更新；`international.enabled=false`，网站 `phone-international-enabled=false`。
- 企业 SenderID 材料已由用户提交，尚未确认审核通过。仍需确认实际发送国家的要求，并完成 Logto HTTP SMS 保存和真实收码验收。
- 中国大陆的 `cn.*` 仍为原 MAS 配置，本次未迁移；桥接 Bearer、频率限制以及其他配置完整保留。

## 验证范围

执行 `git fetch --all`，干净 main 快进合入 `origin/main`，并确认已包含 `origin/dev/yth`；本地测试版本为 `b466da7`。前端 188 项测试、类型检查及生产构建通过；后端 `mvn verify` 252 项测试通过并成功构建 JAR。

新合入的应用代码仅涉及 PayPal，本次短信配置预置继续使用已上线 JAR，没有发布该 PayPal 变更。运行源码仍为 `db23772f44c8891ac95c18e658044c92757ac3b7`，不能将测试的 Git HEAD 视为本次运行版本。

只读查询预检中，国际新加坡 endpoint 返回 HTTP 200 / `INVALID_PARAMETERS`（使用不存在的诊断 MessageId）；杭州 endpoint 返回 HTTP 503 / `ServiceUnavailable`。这只说明查询接口响应情况，不代表发送权限、SenderID 审核、订阅或实际送达已验证。

重启后 30 项无发码检查通过，涵盖短信桥鉴权和输入限制、页面、原登录能力、两个国际开关关闭、三服务和应用健康。没有创建测试用户或会话。

## 备份与回滚

- 备份：`/var/backups/qingfeng-music/20261106T101047Z`（北京时间 18:10:47），数据库 gzip、媒体/配置 tar、部署配置 tar 均校验通过。
- 运行目录：`/opt/qingfeng-music/releases/20261106-155253-sms-bridge`，JAR SHA256 `72becbfef12f6b25f230c0c2d792556655c565b284148892ade2a77dca134b74`，本次保持不变。
- 原配置另保存为备份目录中的 `application-before-enterprise-sms.properties`，权限为 0600；实际仅四项属性值变化，公开国际开关原本即为 false。
- 重启前后作品 124、媒体 100、任务 109、创建者 98、Google 身份 1、OIDC 身份 4、积分账本 1、订单 0，数量完全一致；数据库迁移历史未改变。
- 如需回滚，仅恢复这份原配置并保留原属主/权限，再重启并检查健康。JAR 和数据库无需恢复，不覆盖后续用户数据。

脱敏机器记录见 [evidence.json](evidence.json)。
