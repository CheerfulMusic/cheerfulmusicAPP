# AI 剧情 MV 接入与验收

本功能默认关闭。当前适配 EcomAI Studio 所使用的 NewAPI `/videos` 网关协议，**不是直接调用 Ark API**。不填写虚构模型名，不把 API Key 放入仓库。

私有运行配置示例（仅结构；占位符须替换）：

```properties
qingfeng.video.enabled=false
qingfeng.video.base-url=https://YOUR-GATEWAY/v1/
qingfeng.video.model=YOUR-SEEDANCE-MODEL-ID
qingfeng.video.api-key=${VIDEO_API_KEY}
qingfeng.video.storage-root=/YOUR-PERSISTENT-PRIVATE-STORAGE/music-videos
qingfeng.video.ffmpeg=/YOUR-FFMPEG-PATH/ffmpeg
qingfeng.video.credits-per-project=0
qingfeng.video.poll-delay-ms=10000
```

上线前按现有 RELEASE-GUARD 流程验收迁移 V47，核对编号冲突。不得直接修改生产库。日常本地连接主库时保持 `qingfeng.video.enabled=false`，避免本地后台调度与生产争抢。此文件不授予部署授权。

## 对话操作

- “根据当前歌曲设计剧情 MV，先给我分镜和费用”：Agent 生成结构化剧情，每个镜头 4–15 秒，最多 12 镜头，总长不超过 120 秒。
- 创建前展示逐镜头内容、画幅和配置费用，由用户确认后创建。未配置密钥、模型或正数费用时拒绝提交和扣费。
- “查看 MV 任务 <任务 ID>”或“查看当前作品的 MV”：恢复数据库中的任务。卡片自动查询进度，不占用新的模型调用。
- “下载 MV 任务 <任务 ID>”：返回已完成视频卡片，提供鉴权播放和下载。
- “取消 MV 任务 <任务 ID>”：仅未开始的队列任务可取消并退回同笔积分。
- “重新合成 MV 任务 <任务 ID>”：仅全部镜头 READY 且合成失败时可重试，不重做镜头、不再次扣费。

## 执行与恢复

任务、分镜和扣费在同一事务内落库，owner/request_key 唯一且绑定完整内容哈希。后台通过数据库锁及 600 秒租约认领任务，每次处理一个镜头；一次模型提交不自动重试。镜头提交前标记 SUBMITTING；若进程在上游受理后、保存 ID 前中断，转 UNCERTAIN，人工对照供应商记录核对，不假设失败。

轮询原任务 ID；两小时仍未确认则转 UNCERTAIN，不再自动重复生成或退款。HTTP 失败只重查原任务。各项目按最近处理时间轮转，避免长任务一直占据队首。下载只访问已配置 HTTPS 网关 `/videos/{id}/content`，不接受模型返回的任意 URL，不跟随携带凭证的重定向。下载有大小和读取时限。

镜头完成后，FFmpeg 缩放、拼接、叠加当前作品的原音频，生成 MP4；不使用模型音频。成片从原歌曲开头开始，长度不超过分镜总长及音频长度。**当前为最长 120 秒的剧情片段，不宣称覆盖任意长度整首歌曲，也不保证跨镜头人物完全一致。**

所有视频文件存于非公开、持久化目录，通过本人权限校验的接口提供。部署备份必须包含该目录；多实例须共享同一持久化目录。失败镜头或上游状态不明的任务保留上游记录，由运营核对成本及退款；不自动整单重做。不得手改为 READY 或伪造视频地址。

## 启用前仍需真实验证

1. 确认网关地址、模型 ID、Key、`/videos` 请求格式和 `/content` 下载兼容性。
2. 安装支持 libx264/aac 的 FFmpeg，检查服务用户可执行、存储目录权限及磁盘配额。
3. 按供应商真实报价确定每项目积分价格（本次没有擅定收费，默认 0，禁止提交）；长短片当前同价，启用前应确认该计费策略。
4. 小样本真实生成，验证镜头内容、人物一致性、合成音轨、成功/失败/超时、服务重启和任务恢复。
5. 运行 MySQL 隔离迁移验收及完整发布检查，再由单一发布负责人上线。

自动化测试仅使用 H2 和模拟供应商，不访问生产数据库，不产生真实视频模型费用。实际测试结果见本次提交记录/交付说明。
