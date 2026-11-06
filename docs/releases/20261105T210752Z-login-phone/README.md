# 线上构建基线：20261105T210752Z-login-phone

记录时间：2026-11-06。来源：服务器 `/opt/qingfeng-music/current/app.jar`，只读提取；服务器上没有本项目源码目录或 Git 仓库。

## 这份基线是什么

- `manifest.json`：JAR SHA256、JAR 内 74 个静态资源的路径／大小／SHA256、59 个顶层业务类、5 个配置模板名、数据库现状。
- `server-evidence.txt`：服务器 release 目录里已有的发布证据与 `source-commit.txt` 原文。

线上前端构建产物（55 个文件，约 1.6MB，不含媒体）已同步到本机 `D:/it_ai/online-baseline-20261105T210752Z/`，该目录在仓库外、不进 Git；媒体资源（19 个 mp3/png）只在清单里记录哈希。

## 为什么不是源码

服务器只部署编译好的 JAR。线上 59 个顶层业务类在本地 4892714 中全部有同名源码（0 缺失），此前按线上行为重写的邀请码门禁已让类结构对齐；但编译产物无法逐行还原成可维护的 Vue／Java 源码。

服务器 release 目录里保留的源码提交：

| release | commit |
| --- | --- |
| 20261104-225531-session-management | 2adbc57 |
| 20261105-lyrics-save-brand | 00dd521（当前任何仓库都不存在） |
| 20261105-recharge-login-14f10936 | 4e28987 |
| 20261105-invite-release | 无记录 |
| 20261105T210752Z-login-phone（当前） | 无记录 |

最后两个 release 缺 source-commit.txt，这正是此前无法把线上构建对应到 Git 提交的原因。以后每次发布必须把 `git rev-parse HEAD` 写进 release 目录。

## 与本地代码的关系

- 本地 main / dev/yth（4892714）已包含线上全部后端类、邀请码门禁、Logto 登录开关与 V1–V20 迁移。
- 线上 application-local.yml 多出的 3 条 invite-gate.local.properties import 已同步到本地。
- 线上私有配置 /etc/qingfeng-music/application.properties 与生产 invite-gate.properties 的真实值属于服务器凭据，不进入 Git。
- 数据库：线上 Flyway V20、115 个作品、81 个创建者；本地开发已通过 SSH 隧道 127.0.0.1:13306 共享该库。

## 用途

用于今后核对「线上到底跑的是哪一版」以及回滚比对。它不能替代源码；如需逐行一致，必须找回 00dd521 或当前 login-phone 构建的原始工作区。
