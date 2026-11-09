# 2026-11-09 订单入口与取消操作发布

- 发布源码：f7d06f81d832dc8df7f12b340038b1330b415cd7（本地 main，已包含 origin/main、dev/yth 及线上 cad0163）。
- 修复提交：d482ae3；同事工作目录未提交内容未纳入本次发布。
- 当前版本：/opt/qingfeng-music/releases/20261109T053023Z-orders-f7d06f8。
- 上一版本：/opt/qingfeng-music/releases/20261109T0510Z-cad0163。
- 完整备份：/var/backups/qingfeng-music/deploy-20261109T053023Z-orders；数据库 gzip 与版本/配置/数据 tar 校验通过。
- JAR SHA256：a2b06d9926ae0e930db9b303501cafe0053784c7c6683597936df511ded46eea。
- npm test 241 项通过，mvn verify 319 项通过；类型检查、前端构建、JAR 内前端逐字节核对通过。
- 无新增迁移，数据库仍 V24；配置哈希未变。切换时无进行中的生成任务。
- 公网首页、账号页、充值页返回 200，订单/充值 JS/CSS 与本地构建完全一致，见 public-smoke.json。
- 真实订单取消、实际付款未自动操作。取消按钮的成功/拒绝、已授权恢复并列显示有离线组件回归覆盖；未声称在用户登录会话内完成真实取消操作。

首次发布因目录权限为 root:700，服务无法读取 JAR，健康检查失败，自动恢复旧版本且确认 UP。修正为 root:qfmusic 750，并在停服前增加 runuser 可读性校验后重新备份发布成功。失败版本和首次备份保留供追溯。

回滚：将 current 原子切回上述上一版本，重启 qingfeng-music 并检查健康。此次不需要还原数据库；不得用旧备份覆盖上线后的真实订单或用户数据。
