# cover

独立管理歌曲封面、图片加工任务与封面选择版本。更换封面不修改故事版本，不触发歌曲生成。

- `api/CoverApi`：拥有者鉴权后的上传、列表、选择、读取、AI加工入口。
- `api/SharedCoverAccess`：仅供sharing验证分享令牌后读取已发布的封面快照，不能直接暴露为公开接口。
- `application/CoverService`：存储容量预留、封面选择、编辑准入与分阶段执行。只依赖作品API、媒体容量API、额度端口和本模块端口。
- `CoverFileStore`：由infrastructure的本地/OSS图片存储实现，统一解码校验、去元数据、裁剪和重编码。
- `ImageEditProvider`：由KIE图片编辑适配器实现，只有用户创建编辑任务后才上传图片。付费提交不重试，状态未知保留待核对。
- `CoverRepository`：持久化封面、乐观版本与带租约的编辑任务。

数据迁移V10。分享创建时快照封面ID；修改当前封面不改变既有分享，过期或撤销同时关闭图片访问。
