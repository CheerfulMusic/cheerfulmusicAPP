# 第一阶段：生产迁移基线恢复与 Git Worktree 开发隔离 · 实施报告

- 执行时间：2026-11-10 18:41—19:05（北京时间）
- 基线版本：生产 `685ecc6`（release `20261110T095834Z-legacy-fallback-685ecc6`）
- 授权范围：仅本地仓库与工作区操作。**未执行**：生产数据库写入、生产 Flyway 操作、生产部署/重启、Git commit、Git push、删除旧检出、删除 stash/recovered 分支、覆盖其他会话未提交修改
- 判定口径：**PASS**＝实际执行且有证据；**FAIL**＝实际执行但不符合预期；**UNKNOWN**＝未执行或无法确认；**BLOCKED**＝存在阻断条件，不能继续

---

## 0. 结论速览

| 任务 | 结论 | 一句话 |
|---|---|---|
| A. V27/V28 生产迁移基线恢复 | **PASS** | 原始内容已从生产 binlog 复原，checksum `136625465` / `-1640394573` **逐一精确复现**，已补录到独立分支 |
| B. Worktree 开发隔离 | **PASS** | 2 个独立 worktree 已建立；积分 WIP 以哈希校验快照复制，源工作区 35/35 未被触碰 |
| C. 行尾规范治理 | **PASS（安全性）/ BLOCKED（合并时机）** | 历史迁移 Git 字节零变化、28/28 Flyway checksum 不变；但 24 个文件 JAR 字节会变，将触发发布门禁字节断言 |
| D. V29 迁移预处理 | **PASS（分析）/ BLOCKED（落地）** | 生产最大版本仍为 28、V29 空缺、无冲突；但改号与落地需人工批准，且发布门禁需先改造 |
| E. Git 安全（无 commit/push/删除/覆盖） | **PASS** | 全部满足；主工作区脏文件数变化（43→53）来自另一会话并发写入，与本轮无关 |

---

## 1. 执行前后 Git 检出清单

### 1.1 执行前（18:41:43 实测，非沿用上一轮报告数字）

| 路径 | 远端 | 分支 | HEAD | 脏文件 | 备注 |
|---|---|---|---|---|---|
| `D:/it_ai/人人是歌手` | github CheerfulMusic | main | `685ecc6` | **43**（21 未跟踪 + 22 已修改） | 多会话共用，审计期间仍在被写入 |
| `C:/…/ChatGPT/人人是歌手` | 同上 | `codex/google-login` | `1b47d89` | 124 | 含 17 个未提交迁移副本（另一编号谱系） |
| `C:/…/ChatGPT/cheerfulmusic-release-review-20261105` | 同上 | `codex/release-review-20261105` | `1847ddd` | 441 | **本地改过 V10–V26 迁移内容** |
| `C:/…/ChatGPT/人人是歌手-payment-hardening` | 同上 | `codex/payment-local-hardening-20261105` | `1847ddd` | 105 | 含 11 个未提交迁移副本 |
| `C:/…/ChatGPT/人人是歌手-payment-framework` | 同上 | `codex/payment-framework` | `282a688` | 0 | 已并入 main |
| `C:/…/ChatGPT/cheerfulmusic-integrated-20261105` | 同上 | `codex/integrated-release-20261105` | `4e28987` | 0 | 已并入 main |
| `C:/…/ChatGPT/CheerfulMusicAI工作台` | **gitee yth99/cheerful-music-ai** | develop | `5d96a51` | 37 | **另一远端、另一项目谱系** |
| `D:/it_ai/青风` | — | — | — | — | 存放部署私钥与运行时材料 |

注册 worktree：1 个。stash：1 个。`recovered/*` 分支：14 个。

### 1.2 执行后

| 路径 | 分支 | HEAD | 状态 |
|---|---|---|---|
| `D:/it_ai/人已是歌手` | main | `685ecc6` | **未改动**（本轮未写入任何文件） |
| `D:/it_ai/qingfeng-credit-consumption` | `feature/credit-consumption` | `685ecc6` | **新增**，35 项变更 + 1 份快照清单 |
| `D:/it_ai/qingfeng-migration-baseline` | `feature/migration-baseline-v27-v28` | `685ecc6` | **新增**，4 项变更（均已暂存，未提交） |

`git worktree list` 现为 3 条，全部登记在册。

---

## 2. 各检出的未提交文件保护情况

| 检出 | 保护方式 | 验证结果 |
|---|---|---|
| `D:/it_ai/人已是歌手` | **完全只读**，全程未写入、未 reset/restore/clean/stash | 快照后复核：源 35/35 文件 sha256 与快照一致（**差异 0**）；主工作区未出现本轮新建的 V27/V28 基线文件 |
| `C:/…/人人是歌手` 等 6 处旧检出 | **完全只读**，未触碰 | 未做任何目录操作 |
| 全部 `recovered/stash-*` 分支 | 只读，未删除 | 仍为 14 个 |
| `stash@{0}` | 未 pop、未 drop | 仍在 |

**并发写入实测**：本轮执行期间（18:41→19:05）主工作区脏文件数由 43 增至 53，新增内容包括 `RewriteCharges.java`、`JdbcRewriteCharges.java`、`V28__rewrite_charges.sql`、`credits-refresh.test.ts`、`credits-gate.test.ts` 等。**本轮未向该目录写入任何内容**；增量全部来自另一会话。

> 结论：其他会话的未提交成果**零丢失**。

---

## 3. V27 / V28 原始 SQL 来源

### 3.1 检索过程（逐层排除）

| 检索面 | 结果 |
|---|---|
| 服务器各 release 的 `source.tar.gz`（27 个） | **无任何 V27/V28/V29** |
| 服务器各 release 的 `app.jar`（27 个） | 仅 `20261106-2155-db-sync-v21-v28` 含 V27/V28，但为**旧支付专库谱系**（`V27__paypal_risk_recovery` / `V28__preserve_legacy_paypal_holds`） |
| 本机全部 `db/migration` 目录 | 同上，无目标文件 |
| 本机全部 `qf-infrastructure-*.jar` / `app-*.jar` | 同上，无目标文件 |
| 全部检出（D: + 6 处 C:）的**全部 git 对象** | 仅有 `V27__preserve_legacy_paypal_holds` / `V28__referral_pending`（另一谱系） |
| 14 个 `recovered/stash-*` 分支 | 最高到 V26 |
| 按 DDL 特征串（`idx_work_owner_deleted_updated` / `fk_quick_work` / `uk_quick_request`）全盘反查 | 仅命中本次导出的 binlog 文本 |
| 历史发布包、`online-baseline-20261105T210752Z`、`qf_migration_drills` | 前者仅前端产物；后者是 MySQL 数据目录而非 SQL |

### 3.2 最终权威来源：生产 MySQL binlog

**`/var/lib/mysql/binlog.000004`，2026-11-10 11:19:00—11:30:00 窗口**（只读 `mysqlbinlog` 导出，未对数据库做任何写入）。

binlog 中同时保留两类铁证：

**(1) Flyway 写入 `flyway_schema_history` 的完整行（证明文件名与 checksum）**

```
### INSERT INTO `qingfeng_music`.`flyway_schema_history`
###   @1=27  @2='27'  @3='work soft delete'  @4='SQL'
###   @5='V27__work_soft_delete.sql'  @6=136625465  @7='qfmusic'  @10=1
### INSERT INTO `qingfeng_music`.`flyway_schema_history`
###   @1=28  @2='28'  @3='quick creation'  @4='SQL'
###   @5='V28__quick_creation.sql'  @6=-1640394573 (2654572723)  @7='qfmusic'  @10=1
```

→ 原始文件名、描述、校验和、安装者、类型全部得到独立佐证；`@4='SQL'` 证明确实由 Flyway 执行（而非手工 SQL）。

**(2) 各条 DDL 的原始文本**

```sql
-- V27
ALTER TABLE music_work ADD COLUMN deleted_at TIMESTAMP(6) NULL
CREATE INDEX idx_work_owner_deleted_updated ON music_work(owner_id,deleted_at,updated_at,id)

-- V28
CREATE TABLE quick_creation (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 work_id VARCHAR(36) NOT NULL,
 work_version BIGINT NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 language VARCHAR(12) NOT NULL,
 status VARCHAR(32) NOT NULL,
 lyrics_key VARCHAR(36) NOT NULL,
 music_key VARCHAR(36) NOT NULL,
 revision_id VARCHAR(36),
 task_id VARCHAR(36),
 error_code VARCHAR(80),
 lease_token VARCHAR(36),
 lease_until TIMESTAMP(6),
 next_attempt_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_quick_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 CONSTRAINT uk_quick_request UNIQUE(work_id,request_key)
)
CREATE INDEX idx_quick_due ON quick_creation(status,next_attempt_at,lease_until)
CREATE INDEX idx_quick_work ON quick_creation(work_id,created_at)
```

### 3.3 复原方法与唯一性论证

- 原文**不是**从表结构反推，而是 binlog 中 MySQL 实际接收并执行的语句文本。
- 文件级复原 = 上述语句 + `;` 终止符 + **无注释头**（由 checksum 穷举确定，见第 4 节）。
- 文件落地后校验和与生产历史表**完全相等**，等价于证明：该文件与当年被 Flyway 读取并执行的文件在 Flyway 校验口径下**逐字节等价**（CRC32 全等；32 位碰撞概率 ≈ 2⁻³²，本次候选数约 2×10¹）。
- 因 binlog 中 `@5` 给出的文件名与内容一致，复原结果在"文件名 + 内容 + 校验和"三个维度闭合。

---

## 4. 两个迁移 checksum 计算过程与结果

### 4.1 算法

Flyway `ChecksumCalculator` 语义：

1. 读文件字节（UTF-8，自动跳过 BOM）；
2. 按 `\n` / `\r` / `\r\n` 逐行切分（**行终止符不参与**）；
3. 对每行字节做 CRC32 累加；
4. 结果取**有符号 32 位**。

**算法自校验**：用同一实现复算 Git 中 V1–V26 共 26 个迁移，与生产 `flyway_schema_history` **逐一完全一致（26/26）** → 算法实现可信。

### 4.2 计算过程

以 V27 为例，候选空间与命中：

| 候选 | 注释头 | 分号 | 复算 checksum | 目标 136625465 |
|---|---|---|---|---|
| 1 | 无 | 无/无 | -445688752 | ✗ |
| 2 | 无 | 无/有 | 143079433 | ✗ |
| 3 | 无 | 有/无 | 1189766224 | ✗ |
| **4** | **无** | **有/有** | **136625465** | **✓ 命中** |

V28 同法命中：`);` 结尾 + 两条 `CREATE INDEX` 均带 `;` + 无注释头 → **-1640394573**。

### 4.3 最终结果

| 文件 | 字节数 | 行尾 | BOM | Flyway checksum | 生产值 | 判定 |
|---|---|---|---|---|---|---|
| `V27__work_soft_delete.sql` | 158 | 纯 LF（2 行） | 无 | **136625465** | 136625465 | **PASS** |
| `V28__quick_creation.sql` | 813 | 纯 LF（22 行） | 无 | **-1640394573** | -1640394573 | **PASS** |

### 4.4 全新检出环境复验

用 `git checkout-index` 导出到全新目录（模拟干净检出）：

| 文件 | 检出后字节 | CR 数 | BOM | checksum | 判定 |
|---|---|---|---|---|---|
| V27 | 160 | 2（被 git 转为 CRLF） | 无 | 136625465 | **PASS** |
| V28 | 835 | 22（被 git 转为 CRLF） | 无 | -1640394573 | **PASS** |

→ 检出时的行尾转换**不影响** Flyway 校验和，与算法预期一致。

### 4.5 JAR 内资源一致性

在基线 worktree 执行 `mvn -pl qf-infrastructure process-resources` 后比对 `target/classes/db/migration/`：

| 文件 | 源文件 SHA256 | JAR 资源 SHA256 | 判定 |
|---|---|---|---|
| V27__work_soft_delete.sql | `561c0cf05a6adeba…` | `561c0cf05a6adeba…` | **PASS** |
| V28__quick_creation.sql | `a148c5b31da60edc…` | `a148c5b31da60edc…` | **PASS** |
| V24__site_events.sql（历史） | `8369ee7de6e9…` | `8369ee7de6e9…` | **PASS** |
| V26__hot_song_engagement.sql（历史） | `b98ee807d52f…` | `b98ee807d52f…` | **PASS** |

### 4.6 安全结论

补录后 Flyway 行为预测：**解析到 V27/V28 → 校验通过（checksum 相等）→ 因生产历史表已存在这两个版本 → 不重复执行**。既消除了 `Schema has a version (28) that is newer than the latest available migration (26)!` 告警，又不会改动生产数据。

> ⚠️ **重要约束（已写入文件旁的 README）**：Flyway 的 checksum 覆盖文件**全部文本（含注释）**。因此这两个文件**不能添加任何注释**——加一行注释就会改变 checksum，导致下次启动校验失败、服务无法启动。"这是历史基线补录"的说明只能放在旁挂文档 `db/migration/README.md`（非 `.sql`，不参与 Flyway 校验）。

---

## 5. Worktree 创建情况

| 项 | 结果 |
|---|---|
| 位置 | `D:/it_ai/qingfeng-credit-consumption`（主仓库同级）、`D:/it_ai/qingfeng-migration-baseline` |
| 分支 | `feature/credit-consumption`、`feature/migration-baseline-v27-v28` |
| 基线 | 均为 `685ecc6`（`git rev-parse` 确认） |
| 注册 | `git worktree list` 已登记 3 条 |
| 与主工作区隔离 | 独立目录、独立索引、独立 HEAD；`.git/worktrees/` 元数据由 Git 统一管理 |
| 文件修改风险 | **无共享文件修改风险**：两个 worktree 各自检出 759 个文件，主工作区未被写入 |

基线 worktree 当前暂存（**未提交**）4 项：

```
M  .gitattributes
M  Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration/README.md
A  Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration/V27__work_soft_delete.sql
A  Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration/V28__quick_creation.sql
```

---

## 6. 积分 WIP 保护及迁移情况

### 6.1 原 WIP 归属与状态

- 原 WIP 位于**共享主工作区** `D:/it_ai/人已是歌手`（未提交、未推送），由另一会话在开发中。
- 截止本轮，积分功能已扩展到**两个迁移**：`V27__credit_consumption.sql`（1032 B）与**新增的** `V28__rewrite_charges.sql`（1331 B）。
- WIP 在本轮期间**持续变化**（18:41→19:05 期间新增 5 个文件）。

### 6.2 保护方式（未做任何转移操作）

采用**文件级只读复制 + 哈希清单**，明确**未**执行 `reset` / `restore` / `clean` / `stash pop`：

- 复制时对每个源文件**连续读取两次并比对 SHA256**，不一致则重试（最多 4 次），避免截到并发写入的半成品；
- 复制后逐文件复核目标字节与源字节一致；
- 生成清单 `.wip-snapshot-manifest.json`：逐文件记录 `path / status / sha256 / bytes / sourceModified / stableRead`。

**结果**：**35/35 复制成功，0 缺失，0 读取不稳定**，快照后源侧复核 35/35 未变。

### 6.3 快照文件清单（35 项）

- 后端新增：`credits/CreditConsumptions.java`、`CreditReferences.java`、`CreditsPricing.java`、`RewriteCharges.java`、`persistence/credits/{EnvironmentCreditsPricing,JdbcCreditConsumptions,JdbcRewriteCharges}.java`
- 后端改动：`InspirationRewriteService.java`、`MusicBriefService.java`、`JdbcCompositionRequests.java`、`JdbcUsageBudget.java`、`LyricsCompositionConfiguration.java`、`UsageConfiguration.java`、`CreditsController.java`、`InspirationController.java`、`application.yml`
- 迁移：`V27__credit_consumption.sql`、`V28__rewrite_charges.sql`
- 测试：`LyricsCreditsIntegrationTest.java`
- 前端新增：`credits.css`、`shared/credits/{CreditCostHint.vue,useCredits.ts}`、`lyrics-credit-hint.test.ts`、`credits-refresh.test.ts`、`credits-gate.test.ts`
- 前端改动：`main.ts`、`DraftStudioPage.vue`、`LyricsPanel.vue`、`credit-shortfall.ts`、`credit-balance.ts`、`en.ts`、`CreditsPanel.vue`、`RechargePage.vue`、`inspiration-studio.css`、`nav-credit-balance.test.ts`

### 6.4 未纳入快照的部分（归属另一会话，非积分功能）

`AnalyticsViewController.java`、`AnalyticsSummarySqlTest.java`、`AnalyticsPage.vue`、`shared/analytics/track.ts`、`analytics.test.ts`、`analytics-duration.test.ts`、`App.vue`、`AnalyticsConsentBanner.vue`、`useAnalyticsConsent.ts`、`analytics-consent.test.ts`、`AnalyticsIdentityWhitelistTest.java`、`docs/数据看板*.md` —— 属**分析看板/隐私同意**方向的另一条工作线，**未复制**，原样保留在主工作区。

### 6.5 业务约束核查（只读分析，结论符合要求）

| 要求 | 核查结果 | 判定 |
|---|---|---|
| 幂等唯一键 | `credit_consumption` UNIQUE `(consumption_type, work_id, request_key)`；`rewrite_consumption` UNIQUE `(creator_id, request_key)`；且 `JdbcCreditConsumptions.reserved()` 与 `JdbcRewriteCharges.reserve()` 均在 INSERT 前**显式查重**（唯一键之外的第二道保险） | **PASS** |
| 退款与 `credit_ledger` 一致 | 扣费与退款使用**同一** `CreditReferences.of(kind, work, key)` 引用键；`JdbcCompositionRequests.failed()` 在**同一事务**内完成「状态置 FAILED + 退还积分 + 流水置 refunded」；退款失败随事务回滚，不会出现"显示已退但未退"的中间态 | **PASS** |
| 外键 `creator(id)` | 两张表均 `FOREIGN KEY(... creator_id) REFERENCES creator(id)`；生产 `creator.id` 为 `varchar(36) NOT NULL` 主键，与既有 `credit_ledger.fk_credit_owner` 范式一致 | **PASS** |
| 生成失败/超时/取消不扣费 | 歌词链路：`failed()` 退款 + `expireStale()` 对 **180 秒**未完成的 PENDING 请求按超时退款；歌曲链路：`JdbcUsageBudget` 失败时 `credits.refund(...)`；AI 改写：`RewriteCharges.fail()` 退款。**免费额度不退**（注释明确：模型调用已发生，退还次数会被用于刷免费调用） | **PASS** |
| 表尚未在生产存在 | `credit_consumption`、`rewrite_*` 在生产库均不存在（只读核实） | **PASS** |

---

## 7. `.gitattributes` 处理结果

### 7.1 拟议规则（已在基线 worktree 暂存，未提交）

```
# 统一文本文件行尾，消除 Windows 检出时的 CRLF 漂移（.sql 关系到 Flyway 校验与发布字节比对）
*.sql text eol=lf
*.java text eol=lf
*.ts text eol=lf
*.vue text eol=lf
*.json text eol=lf
*.yml text eol=lf
*.md text eol=lf
```

### 7.2 验证结果（全部在隔离 worktree 实测）

| 验证项 | 方法 | 结果 | 判定 |
|---|---|---|---|
| 历史迁移 **Git 字节**不变 | 规则生效前后 `git ls-tree` / `git ls-files -s` 对比 V1–V28 blob 哈希 | 索引与 HEAD **唯一差异是新增的 V27/V28**；V1–V26 blob 哈希**完全一致** | **PASS** |
| Flyway 校验和不受影响 | 新规则下全新检出，逐个复算 V1–V28 | **28/28 与生产完全一致** | **PASS** |
| 检出字节形态 | 新规则下全新检出 | 28 个迁移**全部纯 LF**（原为 24 CRLF + 2 LF） | 预期变化 |
| 无 BOM | 逐文件字节检查 | 全部无 BOM | **PASS** |

### 7.3 量化影响与合并结论

**当前生产 JAR 中 24/26 个历史迁移是 CRLF 形态**（仅 V24/V25 为 LF，系上一轮发布时人工转换）。因此：

- 启用全局规则后，下一个 JAR 中 **24 个迁移文件字节会改变**；
- `deploy.py` 的断言 `migrations(新JAR) == migrations(现JAR) == plan['migrations']` 是**按 SHA256 逐字节**比较的 → **下一次发布会被该断言拦截**；
- 但 Flyway 侧完全安全（checksum 28/28 不变），生产校验与执行不受影响。

> **合并结论：BLOCKED（合并时机）**。规则本身经实测是安全的，**但不得在发布门禁改造完成前合入并发布**。
> 改造方案（下一阶段执行）：把 `deploy.py` 的迁移比对从**原始字节 SHA256 相等**改为**Flyway 校验和（CRC32）相等**——因为 Flyway 校验的本来就是 CRC32，按字节比较过严且与行尾强耦合；同时保留"JAR 迁移集合必须 ⊇ 线上 JAR 迁移集合"的单调性断言。

### 7.4 其他文本类型的评估

| 类型 | 是否进入 JAR | 影响 | 建议 |
|---|---|---|---|
| `.sql` | 是（迁移资源） | **命中字节比对门禁** | 必须 LF，且需先改造门禁 |
| `.json` / `.yml` | 是（`application.yml`、`legal/*.json` 等） | 不参与"新旧 JAR 字节比对"，仅影响 JAR 体积与字节 | 可直接 LF |
| `.java` | 否（仅 `.class`） | 无 | 建议 LF，减少 diff 噪声 |
| `.ts` / `.vue` | 否（经 Vite 打包） | 无 | 建议 LF |
| `.md` | 部分（`docs/` 不入 JAR） | 无 | 建议 LF |
| `.sh` | 否 | 已有 `text eol=lf` | 保持 |

---

## 8. V29 迁移预处理结论

### 8.1 事实核实

| 项 | 结果 | 判定 |
|---|---|---|
| 生产库当前最大版本 | 28（历史表 28 条，全部 `success=1`） | **PASS** |
| 生产 V29/V30/V31 占用 | 0 条 | **PASS**（V29 空闲） |
| 仓库所有分支占用 V29/V30/V31 | 无 | **PASS** |
| 全机检出存在 V29/V30 文件 | 无 | **PASS** |
| `credit_consumption` / `rewrite_*` 表是否已在生产存在 | 均不存在 | **PASS**（功能确未上线） |

### 8.2 改号方案（V27 → V29，V28 → V30）

> ⚠️ 由于本轮补录了 `V27__work_soft_delete.sql` 与 `V28__quick_creation.sql`，**积分功能的两个迁移都必须改号**：

| 现文件名 | 目标文件名 | 说明 |
|---|---|---|
| `V27__credit_consumption.sql` | **`V29__credit_consumption.sql`** | 与生产 V27 撞号，必须改 |
| `V28__rewrite_charges.sql` | **`V30__rewrite_charges.sql`** | 生产 V28 已被 quick_creation 占用，必须改 |

改号操作要点：

1. 用 `git mv` 重命名（保留历史），**不要新建+删除**，避免丢失 blame；
2. 重命名后**重新计算并记录**两个文件的 Flyway checksum（作为这两个迁移的基准值），后续任何修改都不得再改这两个文件；
3. 迁移描述取自文件名：`credit consumption` / `rewrite charges`，与生产历史表 `description` 列的约定一致；
4. 改号后必须重新执行"占用检查"：生产最大版本 + 1 = V29，且全仓库无 V29 冲突；
5. 落地顺序建议：先只发布迁移（后端开关关闭）→ 确认 `credit_consumption` / `rewrite_free_quota` / `rewrite_consumption` 三表建成且历史表出现 `29`/`30` 且 `success=1` → 再开功能开关放量。

> 本轮**只做方案，未执行改号**（任务书要求"输出后续实施建议即可"）。改号必须在归属会话确认后执行，以免与其正在进行的编辑冲突。

### 8.3 遗留风险点（需人工确认）

| # | 风险 | 说明 |
|---|---|---|
| R1 | 迁移与业务代码同批发布 | `V29`/`V30` 建表与扣费逻辑同批上线，若建表失败而代码已上线，会在运行时抛"表不存在"。建议**迁移先行**（功能开关关闭） |
| R2 | 免费额度与扣费口径 | `rewrite_free_quota` 是**次数型**配额，`rewrite_consumption.charged` 标记是否扣积分；两表语义不同，对账口径需明确 |
| R3 | 退款与免费额度 | 免费调用失败**不退还次数**（设计如此），需确认产品口径认可 |
| R4 | `credit_consumption.task_status` 取值 | 代码中出现 `RESERVED` / `SUCCEEDED` / `FAILED`，但表定义 `VARCHAR(16)`；需确认取值集合完整且不超长 |
| R5 | 发布门禁需先改造 | 新 JAR 将包含 28→30 个迁移，与线上 JAR 的 26 个不同 → `deploy.py` 字节等同断言会拦截（见 7.3） |

---

## 9. 未完成事项与阻断原因

| # | 事项 | 状态 | 原因 |
|---|---|---|---|
| U1 | `.gitattributes` 全局规则合入并发布 | **BLOCKED** | 24 个迁移的 JAR 字节会变，触发 `deploy.py` 字节等同断言；需先改造门禁为 CRC32 比对（方案已给出） |
| U2 | `deploy.py` 迁移比对改造 | **BLOCKED** | 该脚本只存在于服务器各 release 目录、不在版本库；改动属生产侧发布工具变更，超出本轮"不改生产"的授权 |
| U3 | V27/V28 基线文件提交 | **BLOCKED** | 本轮禁止 commit；文件与 README 已在 worktree 暂存待提交 |
| U4 | 积分迁移改号 V27→V29 / V28→V30 | **BLOCKED** | 归属会话仍在编辑同一批文件；需其确认后由其执行，避免冲突 |
| U5 | 积分 WIP 与新 worktree 的合并核对 | **UNKNOWN** | 快照是 19:0x 时刻的只读副本，原 WIP 仍在演进；需归属会话逐文件比对确认 |
| U6 | 旧检出（441 / 124 / 105 脏文件）归档或删除 | **BLOCKED** | 禁止删除旧检出；需人工逐项确认未合并内容后再处理 |
| U7 | `1b47d89`、`5d96a51` 去留 | **UNKNOWN** | 前者不在 main 祖先链上；后者属另一远端谱系；需人工判断 |
| U8 | 真机微信 WebView 复测 | **UNKNOWN** | 本机无法覆盖 |
| U9 | 积分功能上线 | **BLOCKED** | 依赖 U4、U5、U2 |

---

## 10. 下一阶段实施建议

### 阶段二（发布工具与门禁，建议最先做）

1. **把 `deploy.py` 纳入版本库**（当前只在服务器各 release 目录，是发布流程不可追溯的根源），置于 `Qingfeng_AI_Music/deploy/`。
2. **迁移比对改为 CRC32 语义**：把 `migrations()` 从 SHA256 字典换成 Flyway 校验和字典；断言改为
   - 新 JAR 迁移集合 **⊇** 线上 JAR 迁移集合（单调，只增不减）；
   - 对两个集合的交集，**Flyway 校验和逐个相等**；
   - 允许行尾差异（因为 Flyway 本就忽略行尾），但**禁止内容差异**。
3. 补齐审计报告第 4 节 G1–G14 的自动化门禁脚本 `scripts/release-gate.ps1`。
4. 在门禁中加入"生产库最大迁移版本 + 1"检查与"仓库/分支占用检查"，把撞号挡在发布前。

### 阶段三（提交基线与规则）

5. 提交 `feature/migration-baseline-v27-v28`（3 个文件 + README），**先不发布**；此时线上仍是 26 个迁移的 JAR，启动告警保持不变，不影响生产。
6. 阶段二门禁改造完成并演练通过后，再合入 `.gitattributes` 全局规则。
7. 两者合并为一个发布批次：带 V27/V28 基线 + LF 规则 → 新 JAR 28 个迁移 → 启动告警消失，且迁移集合 ⊇ 线上集合，通过新门禁。

### 阶段四（积分功能安全上线）

8. 归属会话在其 worktree 内完成 `git mv` 改号（V27→V29、V28→V30），记录两个新校验和。
9. 归属会话把新 worktree 快照与自身最新 WIP 逐文件比对，合并差异（快照清单 `.wip-snapshot-manifest.json` 提供逐文件 SHA256）。
10. 发布顺序：**先迁移后功能**——第一次发布只带 V29/V30 迁移、后端开关保持关闭，确认三表建成且历史表 `29`/`30` 成功；观察一个业务窗口后再开开关放量。
11. 回滚：功能回滚靠关闭开关；**不回滚数据库**（流水表只写不删）；代码回滚沿用 `previous-release.txt` + `legacyRollback` 流程。

### 阶段五（工作区收敛）

12. 旧检出逐一归档：`cheerfulmusic-release-review-20261105`（441 脏文件，含被本地改过的 V10–V26 迁移）**必须人工比对**后再决定——它是从它构建会直接导致启动失败的检出。
13. 收敛为"一个主检出 + 每会话一个 worktree"，并把规则写入 `AGENTS.md`：禁止在主检出根目录直接开发；禁止对他人未提交文件执行 reset/restore/clean/stash pop。
14. 把 `deploy/cheerfulmusic-com-cn/`、`docs/preview/`、图片与 dryrun SQL 等未跟踪产物纳入明确的忽略或归档清单。

---

## 附：本轮操作边界声明

- **未**对生产数据库执行任何 INSERT/UPDATE/DELETE/ALTER/DROP；仅 `SELECT` 与只读 `mysqlbinlog` 文件读取。
- **未**触发生产 Flyway 任何操作；**未**部署、**未**重启服务。
- **未**修改支付逻辑、积分业务逻辑、登录/邀请码/音乐生成/数据看板任何功能代码。
- **未**执行 `git commit`、`git push`；两个 worktree 的变更均处于**已暂存/未提交**状态。
- **未**删除任何旧检出；**未**删除或 pop 任何 stash / `recovered/*` 分支。
- **未**覆盖其他会话的任何未提交文件；主工作区全程只读（快照后复核 35/35 一致）。
- 所有结论均来自实际执行的命令输出；未执行项一律标注 UNKNOWN 或 BLOCKED。
