# 青风音乐 Java 后端

当前支持匿名会话与一次性作品找回、草稿、DeepSeek根据故事写词、手动歌词版本与确认、KIE Suno歌曲生成适配、音频归档/播放/下载、礼物分享及撤销。模型开关由本机私有配置控制，用户已要求开启歌词与歌曲生成，点击相应按钮才发起模型请求。真实付费生成尚未实测。支持本地与OSS存储适配。详见上级docs/DeepSeek歌词接入.md及KIE-Suno接入.md。

## 启动

IDEA直接运行MusicApplication默认启用mysql模式，从后端config/local.properties读取数据库配置。无需手动填写Active profiles。也可选用工程内置的MusicApplication - MySQL运行配置，它明确固定mysql及后端根目录。修改默认配置后，已启动的旧进程需要在IDEA重新运行一次。无数据库浏览演示请显式选择local或使用run-local.ps1。

本机JDK17、Maven3.6.3；Spring Boot4.1.2、MyBatis4.0.0、Flyway版本迁移。

```powershell
cd Qingfeng_AI_Music
.\scripts\build.ps1
.\scripts\run-mysql.ps1
```

先填写 `config/local.properties` 并准备独立 `qingfeng_music` 库，配置不能指向其他业务库。凭据不进入代码库或日志。详情见上级 `docs/本地配置与API接入.md`。

MySQL 模式启动执行 Flyway V1—V23，包含身份、作品、歌词、媒体、分享、任务、积分、支付、OIDC 等表；不会自动创建数据库。日常本地运行统一连接线上生产主库（见上级 AGENTS.md），不再使用独立开发库或无数据库演示模式。音频默认写入后端工作目录的 `data/audio`，可配置绝对路径；生产私有 OSS 对象以 `oss:` 前缀登记。上级 `scripts/start-main.ps1` 是唯一启停入口，`start-local.ps1` 仅为转发壳。

## 业务入口

- GET /actuator/health：进程健康。
- GET /api/public/system/info：真实能力状态，MySQL模式分享可用，歌词/音乐生成关闭。
- GET /api/public/csrf：获取CSRF令牌。
- POST /api/public/session：带CSRF建立/恢复匿名创建者HttpOnly Cookie。
- POST /api/public/session/recover：一次性找回码恢复原创建者会话。
- GET/POST /api/app/identity/recovery：查看找回码是否存在/重新生成（明文仅返回一次）。
- POST /api/app/works：创建自己的草稿。
- GET /api/app/works?page=0：每页20条，只读取本人。
- GET /api/app/works/{id}：读取本人作品，跨创建者为404。
- PUT /api/app/works/{id}：带expectedVersion更新，版本冲突409。
- GET/POST /api/app/works/{id}/lyrics：历史/保存新的手动歌词版本；POST /{revision}/confirm确认。
- GET/POST /api/app/works/{id}/audio：列出/上传MP3或WAV（20MB）；GET /{asset}授权播放，?download=true下载。
- GET/POST /api/app/works/{id}/generations：任务历史/幂等提交。当前供应商关闭时返回503且不创建任务。
- POST /api/app/works/{id}/generations/{task}/retry-archive：只重试失败音频保存，不重新生成。
- POST/DELETE /api/app/works/{id}/shares：创建7天礼物链接/撤销本作品全部链接。
- GET /api/public/gifts/{token}及/audio：持有效链接免登录读取白名单信息和音频，撤销/过期返回404。

写请求必须携带X-XSRF-TOKEN；身份来自数据库会话，不信任前端ownerId。故事不会自动公开。匿名会话有效期30天，跨设备/清理Cookie前应保存一次性找回码。接口含单实例限流，上传有数据库容量预留保护。

## 工程边界

server→business、infrastructure；infrastructure→business；business→common。Web类型在server，领域API/端口在business，数据库与供应商对象在infrastructure。业务模块为studio、identity、work、lyrics、generation、media、sharing、catalog。

## 验证状态

后端全量测试通过（当前 285 项 / 60 suites），覆盖身份/归属/CSRF、不可变歌词、并发幂等、租约及恢复、未知提交、单个在途生成、只读刷新/归档重试、找回码并发消费、容量、路径和OSS私有ACL边界。自动化数据库为H2 MySQL兼容模式，供应商与OSS使用离线替身；本机MySQL V1—V23、真实重启、跨浏览器作品找回和备份恢复另行实测。详情见上级`docs/第三阶段验收.md`。
