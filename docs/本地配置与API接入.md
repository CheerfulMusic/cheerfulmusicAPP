# 本地运行与服务接入

> ⚠️ **历史文档（2026-11-07 标注）**：文中 `E:\qingfeng_AI_music` 路径、Mureka 音乐服务候选、Flyway V1—V5 均已过期；当前工作区为 `D:\it_ai\人人是歌手`，音乐模型为 KIE Suno，数据库迁移已到 V23。取代关系见 [ARCHIVE](ARCHIVE.md)。

## 本轮范围

先完成PC与手机网页，本地MySQL优先；服务器和App暂缓。OSS可后续配置，音频存储将同时支持本地开发归档与阿里云适配，不要求先购买服务器。

## MySQL私有配置

文件：`Qingfeng_AI_Music/config/local.properties`，已被Git忽略。

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/qingfeng_music?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
spring.datasource.username=填写实际用户名
spring.datasource.password=填写实际密码
```

不要把值贴到聊天里，不要修改另一个项目的数据库。请确保编辑后保存实际文件，而非只修改 `.example`。

本阶段数据库为独立开发库 `qingfeng_music`。启动MySQL模式执行Flyway V1—V3，包含创建者、会话、作品、歌词版本、音频、分享和任务快照。不要手改已执行迁移，后续用新版本SQL扩展。
当前已增量完成V4执行租约/尝试/找回码及V5媒体容量；完整本地交付、启停、OSS配置和备份步骤见[本地交付与维护](本地交付与维护.md)。

```powershell
cd E:\qingfeng_AI_music\Qingfeng_AI_Music
.\scripts\build.ps1
.\scripts\run-mysql.ps1
```

另一个终端启动Vue：

```powershell
cd E:\qingfeng_AI_music\Qingfeng_AI_Music_VUE
npm run dev
```

前端 http://127.0.0.1:5173 。`run-local.ps1`仍用于无数据库骨架模式，不能保存真实草稿。

## 音乐服务候选：Mureka

选择理由是其官方API同时提供歌词、歌词转歌曲及任务查询，可减少首版供应商接入数量；不是对中文质量、账号可用性或价格的保证，必须通过账号实测。

1. 在 https://platform.mureka.ai 注册API平台账号，确认API能力和可用额度。
2. 创建API Key，存入本机 `local.properties` 的 `qingfeng.providers.mureka.api-key`，不放入VITE变量或代码。
3. 真实调用需要明确测试预算；已获歌词1次/歌曲1次授权，但唯一一次歌词请求因配额不足失败，歌曲未提交。当前按用户指示暂停模型调用。
4. 账号就绪后验证中文人声、自定义歌词、生成耗时、查询状态、音频链接有效期与保存条件。

官方入口：

- https://platform.mureka.ai/docs/en/quickstart.html
- https://platform.mureka.ai/docs/api/operations/post-v1-lyrics-generate.html
- https://platform.mureka.ai/docs/api/operations/post-v1-song-generate.html
- https://platform.mureka.ai/docs/api/operations/get-v1-song-query-%7Btask_id%7D.html

账号未就绪时，UI和数据库业务继续推进，生成按钮保持明确不可用，器乐样曲不作为真实生成验收。

## 当前验证结果

- 本机MySQL：已完成真实迁移、会话和草稿读写、权限隔离、乐观锁及Vue端到端联调。
- Mureka：只读账务接口HTTP200，密钥有效；响应包含并发限制1，未提供余额字段，不推断余额数值。
- 用户批准测试上限：歌词1次、歌曲1次。歌词唯一请求返回HTTP429，服务提示当前配额不足；歌曲尚未提交，未自动重试。
- 当前先推进手动歌词、音频导入、试听下载和分享。模型恢复前再核实账号、执行能力与剩余预算；本轮不自动重试。脱敏计数保存在工作区.cache中，密钥只存被Git忽略的本地配置。

## 不依赖模型的本地体验

首页进入制作歌曲，填写故事后保存，点击进入作品详情。保存并确认手动歌词，上传自有MP3/WAV，试听后创建礼物链接。收礼页不包含原始故事，可以用浏览器隐私窗口验证免登录访问。作品详情可撤销本作品全部礼物链接。

音频默认保存于`Qingfeng_AI_Music/data/audio`，被Git忽略。数据库记录与音频目录需要一起备份，只有数据库不能还原声音。上传只检查容器头和大小，未做完整解码、转码或内容识别；损坏文件可能无法播放。

`localhost`或`127.0.0.1`礼物链接只能用于当前电脑验收。手机联调需使用电脑局域网地址启动前端、允许相应本地端口；当前没有部署公网分享。保持同一浏览器、同一访问域名进行创作，避免匿名Cookie被分到不同站点。
