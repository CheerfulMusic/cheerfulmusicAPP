# 首次登录赠送 100 积分

2026-11-06 已发布至 https://cheerfulmusic.com 。源码提交 `3db0cae1cc7e826d863c2e5ff8c2ae2f23ca6347`。

## 发放规则

- 新用户首次成功完成 Google 或 Logto 身份登录，赠送 100 积分；Logto 下的邮箱、手机和已配置社交登录共用此规则。
- 仅首次绑定可信登录身份时发放。匿名访问、作品找回、重复登录、换设备和关联其他登录方式不会重复领取；已有登录身份的存量账号不补发。
- 登录与赠分在同一事务提交；失败一起回滚。使用账号行锁及唯一账本引用 `first-login:<creatorId>` 防止并发重复到账。
- 发放不依赖积分扣费开关。当前生产扣费关闭，已登录账号仍可在账户页查看积分余额；本次没有开启扣费或支付。
- 登录弹窗已增加中英文一次性赠分说明。

## Git 与验证

在独立工作树的 `main` 上执行 `git fetch --all`，合并 `origin/main`、`origin/dev/yth`。两者均为 `a7a309b`，本次相关同事分支已经合入。原工作区的未提交改动保留，功能提交已推送 `origin/main`。

前端 `npm test` 共 138 项通过，类型检查与生产构建通过；后端 `mvn clean verify` 共 211 项通过（业务模块 7 项、服务模块 204 项），包含新增 8 项赠分集成测试。新增测试覆盖两种提供商首次登录、匿名访问和恢复、重复与换设备、跨渠道、存量账号、事务回滚、无效或冲突登录、并发回调。独立代码审查通过。

公网 20 项检查通过：邀请码门禁、5 条页面路由、前端资源哈希、新赠分文案、Google/Logto 登录服务可用、HTTPS Cookie 安全属性、匿名账号余额为 0 且不产生账本记录、测试会话退出、管理健康端点对公网隐藏。创建了 1 个独立匿名验收账号，随后已退出。未执行真实用户的 OAuth 登录；首次到账由集成测试验证。未调用付费生成或支付接口。

## 发布与回滚

- 新版本：`/opt/qingfeng-music/releases/20261106-132227-welcome-credits`
- 保留旧版本：`/opt/qingfeng-music/releases/20261106-a7a309b`
- JAR SHA256：`a833e38ca67c651d23ce15eaee1bd69c396a7744cac2e40227267107c22fc3d0`
- 停服完整备份：`/var/backups/qingfeng-music/20261106T052309Z`（北京时间 13:23:09）。数据库压缩包、媒体/配置归档及额外的应用配置、systemd、Nginx 配置归档均校验通过。
- 线上 JAR 与本地测试构建逐字节 SHA256 一致；服务器 release 内记录源码提交、上一版本、回滚说明及验收证据。
- 发布前后原 117 个作品、97 份媒体、105 个生成任务、89 个创建者、1 个 Google 身份、3 个 OIDC 身份数量一致；原账本和订单均为 0。公网验收另增加上述匿名账号。
- 私有配置哈希不变；Flyway 历史完全一致，仍为 V20，无新增数据库迁移。应用健康 UP，应用、Nginx、MySQL 均 active。

需要回滚时，将 `current` 原子切回保留的旧版本并重启 `qingfeng-music`。本次无结构迁移，账本记录与旧版本兼容；应保留已经发放的积分，不用旧数据库备份覆盖后续用户数据。

机器可读证据见 [evidence.json](evidence.json)；服务器同一 release 内保留 `release-evidence.json`、`public-smoke.json`、`source-commit.txt`、`previous-release.txt` 和 `rollback.txt`。
