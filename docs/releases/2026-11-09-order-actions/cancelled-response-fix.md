# 取消成功后充值仍被阻止

2026-11-09 只读核对主库：截图涉及的订单 1c4bc94a… 已为 CANCELLED；同一 creator_id 没有其他待付款订单。另两笔 PENDING 属于其他账户，不能用于解释此用户的阻止状态，也未操作这些订单。

根因在前端共享 HTTP 响应合同：订单列表、详情及 capture 只接受 CREATING/PENDING/PAID，漏掉 CANCELLED。列表中存在正常的取消订单时，requestJson 会抛 INVALID_RESPONSE；充值页捕获后设置 blocked，账号订单页则显示历史读取失败。此前组件测试直接 mock requestJson，绕过了此真实校验层，未覆盖此缺陷。

修复统一合法订单状态校验，覆盖 CANCELLED/FAILED/UNKNOWN，保留 UNKNOWN 的禁止购买语义；close 端点增加完整字段和状态校验，不再退回通用非空对象检查。只读取服务端结果，不修改订单、余额、账本或后台支付业务。

增加穿过真实 requestJson/response-contracts 的页面测试，只在 fetch 边界替换 HTTP 响应：确认取消后账号页显示已取消、清除待处理及授权恢复提示、切回充值恢复按钮；首次进入时有 CANCELLED 历史也恢复按钮；PENDING/CREATING/UNKNOWN、非法状态及网络失败仍禁止新购买。另增加列表、详情的终态与 close 不完整响应拒绝测试。

针对性测试 34 项通过，前端类型检查及生产构建通过。本轮未执行真实支付、未取消真实订单、未修改主库。修复提交到本地 main；未发布，线上仍为 61da620。按项目 AGENTS.md，开发与合并不代表上线授权，本轮用户仅要求查明并修复。未部署的协议初稿及 stash 没有随此次修复发布。
