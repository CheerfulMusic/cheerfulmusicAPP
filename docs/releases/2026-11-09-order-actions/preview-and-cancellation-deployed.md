# 试听与取消后充值修复上线

2026-11-09 北京时间约 17:26 完成。

- 源码：e2422c5ff8539e1e1c1059790283a12817f96e59；基于线上 61da620，仅合入取消响应校验和试听资源修复，保留 analytics，未纳入协议草稿。
- current：/opt/qingfeng-music/releases/20261109T092510Z-preview-payment-e2422c5。
- 上版：/opt/qingfeng-music/releases/20261109T070116Z-orders-analytics-61da620。
- 备份：/var/backups/qingfeng-music/deploy-20261109T092510Z-orders-analytics；数据库 gzip、版本/配置/业务数据归档完整性与 SHA256 已验证，含上一版保护脚本和许可策略。
- JAR SHA256：6ce8a3463cac9511e3da57abab46f9e9ad8dfbabc5cceec159d8837b3058b053。

前端 npm test 263 项、后端 mvn clean verify 336 项、保护脚本 5 项通过；使用 test/H2，没有向主库写测试夹具。后端完整验证后仅以完整源码 ID 重新打包元数据。最终九首音频及所有 dist 文件逐字节匹配 JAR，制品检查通过。

发布使用共享锁、current 预期版本检查、无活动生成任务检查；备份验证完成后切换。systemd 启动保护更新为校验音频清单/内容/引用集合，实际验证新包通过、旧包拒绝。故障回退会同时恢复旧脚本/策略及旧 current。明确回滚时应先恢复备份中的保护脚本/策略，再切回上版并重启健康检查；不得恢复数据库覆盖新增业务数据。

线上健康 UP；HTML、账号/充值/analytics JS/CSS 与候选制品一致。正常使用服务器配置的邀请码经 HTTPS 官方门禁接口取得临时通行会话，未绕过门禁，邀请码和 Cookie 未输出。九首音频逐一 GET 200、完整字节长度和 SHA256 匹配，首 1024 字节和末 1024 字节 Range 均 206 且 Content-Range/字节内容正确。结果在版本目录 public-smoke.json、audio-smoke.json。没有声称完成浏览器实际听音或真实订单取消；取消后充值的交互与真实响应校验链路由回归测试覆盖，线上只核对发布资源。没有发起实际付款或操作用户订单。

业务 properties 哈希未变，Flyway version/checksum/success 全部前后相同，仍 V25，无新增迁移。共享 main/dev/yth 已推送发布源码，开发结果已合入本地 main。本地额外协议草稿仍未发布，本地运行版本未切换。
