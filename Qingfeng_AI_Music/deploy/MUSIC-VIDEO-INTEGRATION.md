# AI 剧情 MV 接入与验收

本功能默认关闭。支持 Wan 3.0 异步视频接口，以及原有 NewAPI `/videos` 网关协议。不填写虚构模型名，不把 API Key 放入仓库。

私有运行配置示例（仅结构；占位符须替换）：

```properties
qingfeng.video.enabled=false
qingfeng.video.provider=wan
qingfeng.video.base-url=https://maas.qianwenaiapi.com/api/v1/
qingfeng.video.model=wan3.0-video
qingfeng.video.api-key=${VIDEO_API_KEY}
qingfeng.video.storage-root=/YOUR-PERSISTENT-PRIVATE-STORAGE/music-videos
qingfeng.video.ffmpeg=/YOUR-FFMPEG-PATH/ffmpeg
qingfeng.video.ffprobe=/YOUR-FFMPEG-PATH/ffprobe
qingfeng.video.poll-delay-ms=10000
```

上线前按现有 RELEASE-GUARD 流程验收迁移 V47，核对编号冲突。不得直接修改生产库。日常本地连接主库时保持 `qingfeng.video.enabled=false`，避免本地后台调度与生产争抢。此文件不授予部署授权。

## 对话操作

- 歌曲页“制作 MV”打开完整时间轴，服务器用 FFprobe 读取本人音频，按每 30 秒一段拆解，末段按歌曲剩余时长。支持 2 秒至 60 分钟、最大 100 MB 的音频。
- 用户选择 480P / 720P / 1080P，分别为 1 / 2 / 3 积分每秒。仅生成的视频时间收费；结尾静态图片停留免费，图片不发给模型。歌曲不足一秒按一秒计费，成片按实际音频时长裁切。
- 每段可手动填提示词，也可使用 AI 按歌词、音乐风格和已保存创作参数生成可编辑分镜；这不是声学听音分析。AI 分镜复用现有对话准入及免费额度，未授权额外付费时不会购买对话包。
- 每段支持上传 JPG/PNG、设置结尾图片停留秒数；可整段使用图片。图片最大 12 MB、2400 万像素，每首最多 100 张。图片经过解码转码，仅本人及本作品可访问。
- 创建前展示逐镜头内容、画幅和配置费用，由用户确认后创建。未配置密钥或模型时拒绝提交和扣费。生成前再次核对真实歌曲时长、分镜及费用；整支纯图片 MV 不扣积分、不调用 Wan。
- “查看 MV 任务 <任务 ID>”或“查看当前作品的 MV”：恢复数据库中的任务。卡片自动查询进度，不占用新的模型调用。
- “下载 MV 任务 <任务 ID>”：返回已完成视频卡片，提供鉴权播放和下载。
- “取消 MV 任务 <任务 ID>”：仅未开始的队列任务可取消并退回同笔积分。
- “重新合成 MV 任务 <任务 ID>”：仅全部镜头 READY 且合成失败时可重试，不重做镜头、不再次扣费。

## 执行与恢复

任务、分镜和扣费在同一事务内落库，owner/request_key 唯一且绑定完整内容哈希。后台通过数据库锁及 600 秒租约认领任务，每次处理一个镜头；一次模型提交不自动重试。镜头提交前标记 SUBMITTING；若进程在上游受理后、保存 ID 前中断，转 UNCERTAIN，人工对照供应商记录核对，不假设失败。

轮询原任务 ID；两小时仍未确认则转 UNCERTAIN，不再自动重复生成或退款。HTTP 失败只重查原任务。各项目按最近处理时间轮转，避免长任务一直占据队首。Wan 使用 `/services/aigc/video-generation/video-synthesis` 提交、`/tasks/{id}` 查询。成功后仅下载 HTTPS 阿里云 OSS 签名地址，不携带 API 密钥、不跟随重定向；按用户选择传入分辨率，关闭生成音频。原有 seedance 适配器仅访问网关 `/videos/{id}/content`。下载有大小和读取时限。

镜头完成后，FFmpeg 逐段编码视频和结尾图片，再无损拼接视频轨并叠加完整原歌曲音轨。逐段续租，单次进程有超时，避免长歌曲一次性打开全部解码器。**成片与歌曲时长一致，但生成画面不保证跨段人物完全一致。**

所有视频文件存于非公开、持久化目录，通过本人权限校验的接口提供。部署备份必须包含该目录；多实例须共享同一持久化目录。失败镜头或上游状态不明的任务保留上游记录，由运营核对成本及退款；不自动整单重做。不得手改为 READY 或伪造视频地址。

## 启用前仍需真实验证

1. 确认平台地址、模型 ID 和私有 Key；Wan 协议参考 [创建任务](https://platform.qianwenai.com/docs/api-reference/video-generation/wan30-video/create-task) 与 [查询结果](https://platform.qianwenai.com/docs/api-reference/video-generation/wan30-video/query-result)。
2. 安装支持 libx264/aac 的 FFmpeg，检查服务用户可执行、存储目录权限及磁盘配额。
3. 核对用户已确认的 480P/720P/1080P 每秒 1/2/3 积分，以及免费图片时间的扣除；不得使用浏览器或模型自报的歌曲时长计费。
4. 小样本真实生成，验证镜头内容、人物一致性、合成音轨、成功/失败/超时、服务重启和任务恢复。
5. 运行 MySQL 隔离迁移验收及完整发布检查，再由单一发布负责人上线。

自动化测试仅使用 H2 和模拟供应商，不访问生产数据库，不产生真实视频模型费用。实际测试结果见本次提交记录/交付说明。
