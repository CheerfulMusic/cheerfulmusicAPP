# PayPal新购门禁与存量订单保护

2026-11-10。代码仅在本地实现，未部署，未调整现网Live开关。

新增启动配置 `qingfeng.paypal.accepting-new-orders`，默认true，保留原行为。false时，新建订单在数据库、远端网关及账本副作用之前返回503。catalog禁用新购，但原ready、SDK、既有订单查询、核对、capture、resume、close和Webhook继续使用原结算条件。

本轮12项Java实际服务回归通过，使用mock数据库、事务、账本和PayPal网关，无Spring数据库上下文或供应商网络调用。覆盖新建零副作用、catalog/SDK分离、已付读取、查询补记、Webhook、稳定幂等引用、非法验签、存量capture/resume/close、未知404保持PENDING及恢复新购验证。

重复事件测试只证明复用同一账本引用，不证明真实MySQL exactly-once。真实数据库验收按用户选择继续BLOCKED；没有把mock或H2结果当生产MySQL验收。

此配置不是热更新开关。生产暂停/恢复须另批截止时间、在途订单、授权宽限和受控发布。没有新增公开管理接口，没有删除旧单或改变已有积分。

退款、部分退款、争议、拒付、已消费积分调整和完整对账仍未实现闭环。六笔历史404不结案、不认定未付。本地已有支付凭据只确认是Live配置，未复用到测试，也未调用Live API；没有已证明专用Sandbox资源。

新生产模板把新购设为false，仅为未启用模板，不影响现网。代码回归通过不等于资金验收或上线批准。
