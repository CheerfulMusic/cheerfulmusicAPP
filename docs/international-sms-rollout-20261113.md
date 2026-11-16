# 国际短信接入与上线核对（2026-11-13）

## 范围与已核实状态

首批目标为意大利（IT）、英国（GB）、马来西亚（MY）、澳大利亚（AU）、法国（FR）；长期目标是按国家逐一扩展，不把一次英国备案视为全球通行。阿里云控制台工单 6566055、任务 801192724 的 `CheerMusic` SenderID 已获批，备案地区为英国/泽西岛，类型为通知/验证码。AU 备案尚未提交或获批。备案结果不等于实际送达或所有国家获准使用。

2026-11-13 只读核验线上：`current` 指向 `/opt/qingfeng-music/releases/20261112T174249Z-main-0530c44`，源码提交 `0530c444a38db2363e0a6296c52a416b09a14d5f`，JAR SHA256 `e7193ed14ceb273b3eca1e78798bf4995c11bbabcbb494bff44e9baf105314ce`。服务运行中；systemd 的 `ExecStartPre` release guard 成功执行。生产库 `flyway_schema_history` 成功迁移 V1–V43 共 43 条、失败 0 条；当前 JAR 与迁移收据一致。线上发布策略仅批准这个 JAR；新 JAR 必须重新生成并独立审批精确制品合同及数据库兼容证据。

| 开关或依赖 | 线上当前值 | 受控试点目标 |
| --- | --- | --- |
| `qingfeng.auth.sms-bridge.enabled` | `true` | 保持 |
| `qingfeng.auth.sms-bridge.cn.enabled` | `true` | 保持 |
| `qingfeng.auth.sms-bridge.international.enabled` | `false` | 完成连接器与费用保护核验后开启 |
| `qingfeng.auth.logto.phone-international-enabled` | `false` | 首国验证阶段才开启 |
| `qingfeng.auth.sms-bridge.international.sender-id` | `CheerMusic` | 与批准资料一致；逐国核验可用性 |
| `qingfeng.auth.sms-bridge.international.channel-id` | 已设置，具体值未读取；其适用国家未核实 | 先从控制台核实通道覆盖；无法确认时备份后在新私有配置中置空，使 API 不携带可选 `ChannelId`，逐国验证路由 |
| `qingfeng.auth.sms-bridge.international.allowed-regions` | 线上无该字段，旧版本无地区白名单 | 新版本默认拒绝；完成各国规则、通道和费用核对后，可把 `GB`、`IT`、`FR`、`MY` 列入朋友受控测试，`AU` 待备案批准 |
| `qingfeng.auth.sms-bridge.hourly-limit` | `200` | 结合成本与试点人数下调或确认；同时核对阿里云消费暂停阈值 |
| Logto Cloud HTTP SMS 连接器 | 用户于 2026-11-13 确认云端当前是 HTTP SMS，Endpoint 与 `https://cheerfulmusic.com/api/public/auth/sms/deliver` 一致；未独立读取云端配置 | 上线后用脱敏桥接受理日志与受控收码核验实际路径和鉴权 |

上述线上配置仅报告非密钥字段。桥接的每小时限流不能代替阿里云账户级、国家级或号码级的费用/次数暂停设置；这些供应商阈值尚未核实。

## 首批国家规则

下表中的“阿里云注册”特指向阿里云提交 SenderID 注册，不代表当地法规豁免，也不保证 SenderID 一定显示。各国还须确认通道、套餐/计费、实际送达与合规内容。

| 地区 | 阿里云注册与当前状态 | 发送/显示和内容要点 | 首批处理 |
| --- | --- | --- | --- |
| GB / JE | `CheerMusic` 已按英国/泽西岛获批，验证码/通知类型；[SenderID 注册规则](https://www.alibabacloud.com/help/en/sms/user-guide/register-sender-id) | 只在备案地区、类型和通过的内容范围使用；实际运营商呈现待受控验证 | 先开放 GB 受控测试。JE 虽在批准范围内，本轮不自动开放 |
| IT | [阿里云国家规则](https://sms.console.alibabacloud.com/messageProductUserGuide/detail/85/3)显示无需向阿里云另行注册，字母 SenderID 可显示，数字 SenderID 不支持 | 仍须与通道核对意大利 Alias 合规与实际显示；实际送达未验证 | 满足国家规则、路由和费用核对后可列入朋友受控测试 |
| MY | [阿里云国家规则](https://sms.console.alibabacloud.com/messageProductUserGuide/detail/209/3)显示无需向阿里云另行注册；字母 SenderID 通常转为数字短码。该页提到本地品牌需阿里云审核；英国企业是否被当前通道判作非本地品牌，尚未从账户配置证实 | 短信须含品牌；不得含网址、链接或电话号码；阿里云会加 `RM0.00` 前缀。[马来西亚规则](https://www.alibabacloud.com/help/en/sms/product-overview/announcement-on-strengthening-the-control-of-international-sms-in-malaysia)、[前缀说明](https://www.alibabacloud.com/help/en/sms/product-overview/explanation-on-malaysia-sms-increase-rm0-00)。[定价说明](https://www.alibabacloud.com/help/en/sms/product-overview/pricing)中的 Global 标准套餐不含 MY，需核对可用套餐/计费路径 | 核实品牌审核适用性、文案、路由和价格后可列入朋友受控测试；不承诺显示 `CheerMusic` |
| AU | [阿里云国家规则](https://sms.console.alibabacloud.com/messageProductUserGuide/detail/227/3)要求注册 SenderID，预计材料齐全后 1–2 周；[澳大利亚合规说明](https://www.alibabacloud.com/help/en/sms/product-overview/australian-sms-compliance-requirements)称未注册 SenderID 可被替换或拦截 | 当前尚未提交/获批，不能把英国审批沿用到 AU；英国等无 ABN 的海外企业可提交本国公司注册证明或登记摘录，指定管理代表需数字身份核验，并证明 SenderID 与品牌关联 | 保持关闭，待独立审批、通道和受控测试完成 |
| FR | [阿里云国家规则](https://sms.console.alibabacloud.com/messageProductUserGuide/detail/108/3)显示无需向阿里云另行注册；字母 SenderID 可显示，但 NRJ Mobile 可能改为数字短码 | 营销短信涉及白名单和时段限制；本项目仅发送验证码。需确认模板与实际呈现；[阿里云 FAQ](https://www.alibabacloud.com/help/en/sms/support/faq-2) | 满足国家规则、路由和费用核对后可列入朋友受控测试 |

AU 报备入口：阿里云短信控制台 `Go Globe → Before Sending → SenderID → Registration → Register Sender ID`，选择 AU，使用英文填写公司、`CheerMusic` 与验证码样例，按页面指引上传英国公司证明、授权与品牌关联材料；管理代表完成身份核验，待阿里云与澳洲审核、控制台显示 Approved 并收到激活通知。当前只核对要求，未提交申请。详细字段和材料清单以该账户表单为准。

## 发布与验证顺序

1. 确认 Logto Cloud 中实际生效的 HTTP SMS 连接器端点、Bearer 鉴权、请求体及验证码类型；保留当前连接器配置备份。核对阿里云国际通道、余额/套餐、国家限制和账户/国家/号码消费暂停阈值。核实线上非空 `ChannelId` 是否覆盖所有测试国家；若无法证实，备份后从新私有配置去掉该可选字段，让 API 不发送 `ChannelId`，再逐国验证。只使用已授权的受控测试号码。
2. 先确定发布基线。当前本地 `main` 已包含支付 V44，线上仍为 V43；直接把当前 `main` JAR 作为“仅短信”发布会携带尚未放行的 V44。若采取短信专用维护制品，从线上提交 `0530c444…` 派生，仅移植短信改动，并让 JAR 保持 V1–V43 与现有支付协议；同时按项目要求把改动合并到本地 `main`。此专用发布与日常主线发布路径不同，必须明确审批、单独审阅差异及兼容证据。
3. 按项目上线流程先 `git fetch --all`，检查工作区与分支；确认发布候选包含线上提交。运行前端 `npm test`、构建以及后端 `mvn verify`，生成与完整源码提交绑定的 JAR，并核对打包前端与迁移集合。不得把 V44 当作已放行迁移。
4. 上线前在共享发布锁内备份现有 release、私有配置、发布策略和数据库；验证恢复可用及新旧制品对当前数据库兼容。生成受审的制品合同、schema 证据和精确 JAR 策略项；先以服务账户运行 release guard 验证候选。测试、备份或 guard 失败则停止。
5. 可按国家规则与实际路由准备 `GB`、`IT`、`FR`、`MY` 的朋友受控测试；MY 还需核实品牌审核适用性及套餐。每个国家仅在准备完成后加入白名单，部署并重启后检查服务健康、公开认证状态中的 `phoneAllowedRegions`、中国大陆登录不受影响、桥接授权拒绝行为，以及该国受控号码的真实接收和阿里云发送记录。收到供应商 API 成功响应不等于用户已收到。记录脱敏回执、费用、延迟和发送失败原因。
6. 逐国验证后再扩大可用范围；AU 在独立备案获批之前维持关闭。观察错误率、费用和限流，异常时先关闭国际开关和地区白名单。

## 回滚

保留旧 `current` 目标、配置、策略及数据库备份。若专用制品未执行新迁移且支付协议不变，先关闭国际开关并恢复 Logto 原连接器，再在 guard 对旧 JAR、当前数据库兼容性和政策批准均通过的前提下切回旧版本并复核健康。若 V44 或新的资金写入已发生，不能直接切回不理解新协议的旧 JAR，也不能用旧数据库快照覆盖新订单/Webhook；应采用受审的兼容前滚或兼容回滚制品。任何回滚都不能绕过 release guard。
