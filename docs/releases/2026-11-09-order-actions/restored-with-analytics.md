# 订单修复恢复上线，保留 analytics

- 时间：2026-11-09，北京时间约 15:01 完成切换。
- 发布完整源码：`61da6203280d8a4f7409b19b07bd83c5bd02a899`。
- 已包含 analytics 线上基线 `880e59c`、本次已提交 dev/yth 更新 `3c01fac`、订单修复及之前已上线修复 `f7d06f8`。
- 生产目录：`/opt/qingfeng-music/releases/20261109T070116Z-orders-analytics-61da620`。
- 上一目录：`/opt/qingfeng-music/releases/20261109T063202Z-analytics-880e59c`。
- 备份：`/var/backups/qingfeng-music/deploy-20261109T070116Z-orders-analytics`。数据库 gzip、旧版本/配置/业务数据 tar 均检查可读取且 SHA256 校验通过；未实际执行恢复演练。
- JAR SHA256：`ed2e7380e3f371d4305f4ca477fbc222bc2e0d101e95dac223379edcf84ba996`。

前端 247 项测试通过，类型检查、构建通过；后端 `mvn clean verify` 336 项测试通过，隔离 test/H2 数据源，不向主库写测试数据。随后只修正构建元数据占位符，跳过重复业务测试重新打包，核验嵌入提交号为上述发布源码，51 个前端文件逐字节匹配 JAR。保护脚本 4 项测试通过。analytics 页面和后端统计/采集控制器与线上基线保持一致。

实际部署前核对无进行中的生成任务，以共享锁和 current 预期值检查防并发覆盖；备份验证完成后才切换。首次预检因 Windows 清单 CRLF 导致严格比较拒绝，在停服前退出；清单标准化为 LF 后执行成功。应用配置 SHA256 不变，Flyway 全部迁移的 version/checksum/success 前后完全一致，仍为 V25，没有新增迁移。未操作真实付款或取消真实用户订单。

实际安装 `/opt/qingfeng-music/verify-release.py`、`/etc/qingfeng-music/release-policy.json`、systemd `90-release-guard.conf`，均 root 管理且服务用户不可写；systemd ExecStartPre 已实际执行并 exit 0。以 qfmusic 账户验证新制品通过，旧 880e59c 制品被拒绝。策略只批准此次 JAR，后续新版本须经验证明确批准；不能仅替换 current 使用旧发布包。root 主动移除或修改保护不在该防护范围内。回滚须先核对备份中的旧 JAR，并明确添加临时旧 JAR 许可，再切换和健康检查；不得还原主库覆盖上线后业务数据。部署故障处理会恢复上一版本及安装前保护状态。

公网 `/`、`/account`、`/recharge`、`/analytics` 返回 200，HTML 和入口/账号/充值/analytics 的 JS、CSS 与发布 JAR 逐字节一致，详见服务器版本目录 `public-smoke.json`。本机另一次独立公网检查再次通过：

- 入口 `/assets/index-B2RLjiiV.js`。
- 账号 `/assets/IdentityPage-C52BESKg.js`，含 `cancel-order`、`payment-orders`。
- 充值 `/assets/RechargePage-C5hz2rJn.js`，含 `/account#payment-orders`。
- analytics `/assets/AnalyticsPage-WDf90Fsk.js`。

浏览器工具因无法可靠识别 Windows 当前网址停止，未完成已登录会话中的视觉验收或真实取消；不得将资源验证说成真实订单操作验证。取消按钮成功/服务端拒绝及已授权订单同时显示恢复/取消操作由离线组件测试覆盖。

已将发布源码快进推送共享 main 与 dev/yth，并在另一个本机仓库仅 fetch 同步远程信息，保留其未提交业务工作。开发成果已合入本地 main；本地 main 中额外存在未发布的协议初稿，不等同生产制品。未完成的协议开发另存 git stash `preserve unfinished legal consent before authorized order hotfix 20261109`，未混入发布。本地运行 JAR 未切换，不能宣称本地运行版本同步生效。
