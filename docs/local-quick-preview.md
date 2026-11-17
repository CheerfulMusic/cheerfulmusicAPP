# 本机一键创作预览

本次按用户明确授权，在备份后为生产主库 `qingfeng_music` 执行 V27、V28；没有发布网站或重启线上服务。

- V27：`music_work.deleted_at` 与列表索引。
- V28：`quick_creation` 表、外键、唯一约束和领取索引。
- 备份：`/var/backups/qingfeng-music/before-quick-v27-20261110T025317Z`，数据库压缩包已校验，包含配置与迁移历史备份。

## 当前本机执行方式

浏览器入口为 `http://127.0.0.1:5173/create`，后端仅监听 `127.0.0.1:8080`，数据库经 `127.0.0.1:13306` SSH 隧道连接主库。

`qingfeng.quick.scheduler-enabled` 可以独立启用写词、确认歌词、排入音乐队列的编排执行器；未配置时沿用 `qingfeng.generation.scheduler-enabled`。普通音乐与封面调度继续关闭，本机不接管线上的歌曲提交、查询和音频归档队列。这些音乐阶段由线上现有执行器完成。

当前预览的私有启动脚本与运行记录在忽略提交的 `.cache/runtime/quick-main`、`.cache/start-quick-backend.ps1`。本机 IDEA 使用的 `Qingfeng_AI_Music/config/local.properties` 已启用一键编排，Flyway 和 SQL 初始化保持关闭，重启 IDEA 后须重新编译当前源码。上述本机配置不应复制到服务器。

前端预览将 `/api/public/system` 与 `/api/app/works/{work}/quick-creations` 指向本机，其余既有线上业务、音频转发保持原路径，避免本机缺少线上音频文件。代理配置位于忽略提交的 `.cache/runtime/production-main/vite.frontend-preview.config.mjs`。

## 日常入口与验证边界

`scripts/start-main.ps1` 仍使用线上匹配版本，明确关闭一键、普通音乐与封面后台调度。它不会自动进入这次源码预览模式。日常私有主库配置中的一键调度同样保持关闭。

已通过后端 `mvn verify`、数据库迁移历史校验、8080 健康检查，以及从 5173 读取 `quickCreation=true` 和创作页。验证没有创建主库测试作品或提交付费模型请求，真实歌词与音乐质量由用户手动测试。

停止预览可运行 `scripts/stop-main.ps1`，它按运行记录核对并停止本机进程。恢复原日常入口前，如需恢复 IDEA 的常规行为，将本机 `config/local.properties` 的 `qingfeng.quick.scheduler-enabled` 改为 `false`。不要为恢复本机启动而删除生产表或回退迁移历史；测试期间新增的生产数据应保留。
