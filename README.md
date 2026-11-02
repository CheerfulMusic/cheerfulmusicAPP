# 青风音乐

面向电脑与手机网页的音乐礼物创作项目，采用Cheerful Music品牌主题，支持中英双语。当前实现：故事草稿、DeepSeek歌词编写与版本确认、KIE Suno歌曲生成适配、音频上传/试听/下载、作品管理、7天礼物分享与撤销、一次性作品找回。模型能力由后端私有配置开启，外部模型认证已验证，真实付费生成结果尚未实测。

## 本地体验

环境：JDK17、Maven、Node24或兼容版本、MySQL。维护脚本使用PowerShell7。数据库私有配置位于`Qingfeng_AI_Music/config/local.properties`，不要提交到仓库。

首次检出请先安装前端依赖（`cd Qingfeng_AI_Music_VUE` 后运行 `npm ci`），将后端`config/local.properties.example`复制为`config/local.properties`并填入本机数据库配置，在MySQL创建独立的`qingfeng_music`数据库。API密钥按需填入私有文件或环境变量。示例配置默认关闭付费生成；IDEA直接运行MusicApplication默认连接MySQL，Flyway自动执行数据库迁移。

```powershell
./scripts/build-local.ps1
./scripts/start-local.ps1 -Port 8081
```

打开`http://127.0.0.1:8081`。前后端在同一个本地进程运行，详情与礼物页支持直接打开和刷新。8080如有旧进程，不需要覆盖它。停止本脚本启动的服务：

```powershell
./scripts/stop-local.ps1
```

需要前端热更新时用`start-local.ps1 -Dev -Port 8081 -WebPort 5173`；同一局域网手机调试加`-Lan`，用电脑的局域网IP访问。生产公网HTTPS与App未部署。

## 代码位置

- `Qingfeng_AI_Music`：Java模块化单体，common/business/infrastructure/server。
- `Qingfeng_AI_Music_VUE`：Vue3、TypeScript、Pinia、Router，响应式Web/H5。
- `scripts`：统一构建、启停、备份、独立恢复、存储维护。
- `docs`：架构、实施计划、配置与各阶段验收证据。
- `ui-prototype`：保留已确认的设计原型。

开始操作前可阅读[本地交付与维护](docs/本地交付与维护.md)。当前完成范围与限制见[第三阶段验收](docs/第三阶段验收.md)。
