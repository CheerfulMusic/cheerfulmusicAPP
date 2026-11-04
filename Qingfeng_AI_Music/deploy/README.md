当前可用上级scripts/build-local.ps1生成包含Vue的Java交付包，通过start-local.ps1启动本地MySQL模式；深层路由刷新与API鉴权已实测。详见docs/本地交付与维护.md。

2026-11-02已部署至 https://cheerfulmusic.com ，下文为部署前的准备记录；当前实际配置和验收结果以 `../../docs/阿里云部署.md` 为准。

## 阿里云部署准备（2026-11-02）

目标服务器：`47.84.5.172`，域名：`cheerfulmusic.com`。已核实 A 记录指向该服务器；尚未通过 SSH 登录，以下模板尚未在服务器安装或验证。

- `qingfeng-music.service`：systemd 服务模板，使用专用用户 `qingfeng`，从 `/opt/qingfeng-music/current/app.jar` 启动；需先创建用户及 `/var/lib/qingfeng-music/audio` 并赋予目录权限。
- `cheerfulmusic.nginx.conf`：HTTP 初始化代理模板。先检查现有站点，避免覆盖；安装后运行 `nginx -t`，配置有效证书和 HTTPS 跳转后才进行公网验收。
- `production.properties.example`：生产配置覆盖项。服务器私有配置为 `/etc/qingfeng-music/application.properties`；保留原模型 Key 与模型开关，替换本机数据库凭据，权限限制为服务用户可读，不放入仓库或前端。

前端通过 `npm run build` 构建，后端 Maven 的资源配置会把 `dist` 打入 JAR，因此一次部署同时包含前后端。数据库需独立创建 `qingfeng_music` 和专用账号，Flyway 在首次启动时迁移；已有数据库应先备份。音频使用持久化目录，数据库与音频均需配置备份。无需将本机数据库密码或本机历史数据直接迁入服务器。

准备完成后检查：本机 `127.0.0.1:8080/actuator/health`、HTTPS 首页、深层页面刷新、会话 Cookie 的 Secure 属性、草稿保存与重新读取。真实付费生成应单独验证并记录费用；普通部署检查不调用付费生成。
