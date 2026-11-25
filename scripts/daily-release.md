# 人人是歌手每日正式发布

Windows 任务 QingfengMusic-DailyRelease 在北京时间每天 19:00 运行，使用 yth 登录会话。这台电脑需要开机、联网并保持登录。错过时段不补发。任务的下一次运行时间和状态可用 Get-ScheduledTaskInfo QingfengMusic-DailyRelease 查看。

入口 daily-release.py 从 SourceMain 指定的开发 main 工作树执行 git fetch --all，在独立索引之外计算已提交的本地 main 与 origin/main 的合并结果，并固定候选提交，通过本机已配置的阿里云 Workbench 和 ECS 执行助手访问深圳生产实例。当前版本相同就退出。候选必须包含线上提交。数据库迁移变化转专项发布。

有新提交时，在短路径下的临时 Git 副本中运行候选提交的 release_gate.py。复用项目的 Node/npm/Maven 缓存，临时启动两个绑定回环地址的 MySQL 8.0.46 实例（23316、57346），验证实例身份并为本次测试创建独立数据库。正式模式在前端测试、构建和后端 Maven verify 通过后，将开发 main 快进至已测试提交，普通推送到远端 main，核对提交号后上传 JAR、提交清单、构建契约及校验信息。

服务器流程使用项目共享发布锁，校验当前版本和数据库迁移记录，备份当前版本、配置、数据库，在隔离 MySQL 中实际恢复备份并核对。登记产物批准信息后，使用线上启动守卫校验新旧版本，再原子切换、重启并检查服务、首页、关键页面和资源。切换失败会尝试恢复上版及原批准策略。深圳服务器保存备份和发布回执。Windows 运行日志位于 D:\it_ai\qfr\release-logs。

本机安装参数（从 D:\it_ai\qf-daily 的发布工作树运行，使用 D 盘独立缓存）：

    .\scripts\install-daily-release.ps1 -Scratch D:\it_ai\qfr -JavaHome D:\jdk17 -CacheRoot "D:\it_ai\qf-daily\.cache" -SourceMain "$env:USERPROFILE\Documents\ChatGPT\人人是歌手\.cache\worktrees\welcome-credits-20261106" -Publish

手工演练使用同一 Python 入口和对应的 --scratch、--java-home、--cache-root、--source-main 参数，省略 --publish。加 --verify-current 可在版本相同时执行完整本地测试与构建。独立执行演练时避开定时窗口。安装账户须能使用 Git、项目依赖缓存、Python 3.11+、PyMySQL、Maven、Workbench 和原有深圳 ECS 执行助手。

未提交文件保留在原工作树。合并冲突、快进会覆盖开发改动或测试期间分支变化时中止并写入日志。演练不更新本地主分支、不推送或切换服务器。推送成功而服务器发布失败时，保留远端提交，下一轮重新核对实际线上版本并验证重试。

## 首页 SEO 双地发布收尾

首页的中文和英文搜索品牌、标题、摘要、别名和图标统一维护在 `Qingfeng_AI_Music_VUE/src/shared/i18n/home-seo.ts`。页面实质更新时更新对应语言的 `lastModified`。前端构建会生成两种语言的初始 HTML、站点地图及 `seo-release.json`，JAR 打包时包含这份 SEO 清单。保留上一版清单，下一轮可以只通知发生变化的首页。

首页 SEO 随同一次已批准的双地发布上线。深圳和新加坡可以使用不同的应用包，构建时使用同一版首页 SEO 配置。两地都切换并通过常规健康检查后，用已有授权的本机转发分别直连两个活动应用。新加坡只读应用只服务根首页和静态资源，英文首页和站点地图由主站承接。

使用两地各自的新 JAR 或其 SEO 清单校验两地及公网。以下端口是本机转发示例。

    python scripts/seo-release.py --manifest <shenzhen-jar-or-seo-release.json> --singapore-manifest <singapore-jar-or-seo-release.json> --previous <previous-jar-or-seo-release.json> --shenzhen-app http://127.0.0.1:18081 --singapore-app http://127.0.0.1:18082

首个包含 SEO 清单的版本可以省略 `--previous`。脚本先对比两地包内的 SEO 清单，再检查新加坡只读应用的就绪状态、根首页和图标，以及深圳和公网的中英文初始 HTML、站点地图、图标和 robots 抓取规则。任一步失败都会使抓取通知暂缓。已批准发送通知时，将 `--notify --receipt <release-specific-receipt.json>` 加到同一命令。通知脚本通过环境变量读取 `QF_SEO_INDEXNOW_KEY` 和 `QF_SEO_BAIDU_TOKEN`，未配置的平台会标记为 `not_configured`。IndexNow 先校验公网的根目录验证文件。百度从已验证站点的「普通收录 → API提交」取得 Token。百度接口要求可验证的 HTTPS；如果当前接口仅支持 HTTP，沿用站点地图或手动提交。

百度和必应的回执存入该版本的 JSON 文件。重试同一版本时复用回执路径，已接收的通知会跳过。IndexNow 的 HTTP 202 记录为等待密钥验证，百度只接收部分 URL 时保留回执供人工确认。Google、搜狗和 360 可在有 Sitemap 权限时一次登记同一个 `https://cheerfulmusic.com/sitemap.xml` 地址。提交回执与搜索结果验收分别记录。
