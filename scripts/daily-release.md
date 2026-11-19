# 北京时间 19:00 发布

两名开发者都推送到 origin/main。脚本在 fetch 后固定提交，在独立工作树调用该提交的 release_gate.py，使用项目固定的 Node、离线 npm/Maven 缓存和 Java 17。演练会通过 SSH 读取线上 JAR 内的提交号。相同版本直接退出。缺少提交标记、候选版本未包含线上版本或包含数据库迁移文件变更时停止，迁移走专项发布。

在本地 main 工作树运行下面的命令，替换为发布账户实际使用的 SSH 别名、仓库内已准备好的依赖缓存和 JDK 路径。如项目测试需要隔离 MySQL，传入已有的 --cohort 清单（安装入口对应 -Cohort）。使用 Python 3.11 或更高版本。部署账户需要非交互 SSH、已核对的主机密钥，以及执行服务器发布步骤所需的 sudo 权限。

    python scripts/daily-release.py --host music-prod --java-home "D:\jdk17" --cache-root ".cache"
    .\scripts\install-daily-release.ps1 -SshHost music-prod -JavaHome "D:\jdk17" -CacheRoot ".cache"

第一条单次演练，第二条注册每天 19:00 演练。正式授权后在安装命令加 -Publish；已有同名任务时确认替换。任务使用当前已登录的 Windows 账户，机器需保持开机且时区为北京时间，错过不补跑。可通过任务计划程序禁用。每次执行日志位于 %LOCALAPPDATA%\QingfengMusic\release-logs，失败返回非零退出码。

正式模式校验上传 JAR、检查当前版本、运行备份服务并校验新备份，保存旧 JAR 和服务配置。切换后检查本机健康状态和公网首页，失败则切回旧目录并复查。涉及本次业务变更的专门验收需按变更内容补充。备份校验覆盖归档可读性，完整数据库恢复演练沿用项目已有流程。
可在服务器安装可执行的 /usr/local/sbin/qingfeng-release-smoke，接收当前提交号，执行本次功能的只读验收；非零退出触发回退。
