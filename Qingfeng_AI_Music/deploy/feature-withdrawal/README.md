# 线上临时下线智能助手和 MV 生成

2026-11-17 用户要求：先从线上移除，保留本地开发，闭环验收后再恢复。

本目录只提供 Nginx 边缘临时下线措施，不替换业务 JAR、不修改数据库、不发布本地未验收功能。普通创作、歌词和音乐服务保持原版本。Agent 的全部入口/API 禁用；MV 仅允许 GET/HEAD，已有成片仍走原权限校验。网页加载同源脚本隐藏入口，并拦截 SPA 进入 /agent。已打开的旧网页需要刷新，接口限制即时生效。

## 已应用记录

- 时间：2026-11-17
- 应用版本保持 `/opt/qingfeng-music/releases/20261117-all-features-5ce63da`
- 配置备份 `/srv/qingfeng-data/qingfeng-release-backups/feature-withdrawal-20261117T042729Z/cheerfulmusic.conf`
- 服务静态文件 `/var/lib/nginx/feature-withdrawal/`
- `nginx -t` 通过后热加载；应用服务没有重启。
- 公网首页与 `/create` 返回 200，HTML 包含下线脚本；脚本返回 200。
- `/agent` 与 `/agent/` 返回 302 到 `/create`。
- Agent API、MV 创建与准备接口返回 403 `FEATURE_WITHDRAWN`。
- 未登录的原系统信息和 MV 内容接口在直连应用及公网均返回原有 `INVITE_REQUIRED`，不是下线规则阻断读取。
- 应用与 Nginx active，应用 health UP。
- 下线前核对没有 RUNNING/SUBMITTING/UNCERTAIN 的视频镜头；已有 READY 成片保留。

## 恢复条件与操作

只有用户明确同意恢复上线，且本地闭环验证完成后才恢复。日常发布不能顺便移除这层限制。

恢复前先备份当时 Nginx 配置；确认备份以来没有其他配置变更。如果有变更，合并移除 feature-withdrawal 的 location 与 sub_filter，不能直接覆盖其他变更。没有其他变更时可以恢复以上配置备份，再执行 `nginx -t`，成功后 reload nginx。不要删除作品、MV 文件、积分账本或本地功能代码。

`apply.py` 适用于此记录的首次下线，已有规则时会拒绝重复应用。脚本失败时自动恢复原配置；不把重新执行脚本当成恢复上线操作。
