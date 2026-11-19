# 人人是歌手每日正式发布

Windows 任务 QingfengMusic-DailyRelease 在北京时间每天 19:00 运行，使用 yth 登录会话。这台电脑需要开机、联网并保持登录。错过时段不补发。任务的下一次运行时间和状态可用 Get-ScheduledTaskInfo QingfengMusic-DailyRelease 查看。

入口 daily-release.py 从 SourceMain 指定的开发 main 工作树执行 git fetch --all，在独立索引之外计算已提交的本地 main 与 origin/main 的合并结果，并固定候选提交，通过本机已配置的阿里云 Workbench 和 ECS 执行助手访问深圳生产实例。当前版本相同就退出。候选必须包含线上提交。数据库迁移变化转专项发布。

有新提交时，在短路径下的临时 Git 副本中运行候选提交的 release_gate.py。复用项目的 Node/npm/Maven 缓存，临时启动两个绑定回环地址的 MySQL 8.0.46 实例（23316、57346），验证实例身份并为本次测试创建独立数据库。正式模式在前端测试、构建和后端 Maven verify 通过后，将开发 main 快进至已测试提交，普通推送到远端 main，核对提交号后上传 JAR、提交清单、构建契约及校验信息。

服务器流程使用项目共享发布锁，校验当前版本和数据库迁移记录，备份当前版本、配置、数据库，在隔离 MySQL 中实际恢复备份并核对。登记产物批准信息后，使用线上启动守卫校验新旧版本，再原子切换、重启并检查服务、首页、关键页面和资源。切换失败会尝试恢复上版及原批准策略。深圳服务器保存备份和发布回执。Windows 运行日志位于 D:\it_ai\qfr\release-logs。

本机安装参数（从 D:\it_ai\qf-daily 的发布工作树运行，使用 D 盘独立缓存）：

    .\scripts\install-daily-release.ps1 -Scratch D:\it_ai\qfr -JavaHome D:\jdk17 -CacheRoot "D:\it_ai\qf-daily\.cache" -SourceMain "$env:USERPROFILE\Documents\ChatGPT\人人是歌手\.cache\worktrees\welcome-credits-20261106" -Publish

手工演练使用同一 Python 入口和对应的 --scratch、--java-home、--cache-root、--source-main 参数，省略 --publish。加 --verify-current 可在版本相同时执行完整本地测试与构建。独立执行演练时避开定时窗口。安装账户须能使用 Git、项目依赖缓存、Python 3.11+、PyMySQL、Maven、Workbench 和原有深圳 ECS 执行助手。

未提交文件保留在原工作树。合并冲突、快进会覆盖开发改动或测试期间分支变化时中止并写入日志。演练不更新本地主分支、不推送或切换服务器。推送成功而服务器发布失败时，保留远端提交，下一轮重新核对实际线上版本并验证重试。
