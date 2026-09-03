# CORE_PATCHES — 核心文件改动登记

> 工程纪律（见 chatView/docs/05 §3.3 与 docs/04 §6.2）：fork 二开**优先新增**（crabc-agent 模块/新表/新配置），少改核心。
> **凡改动上游已有文件，必须在本文档登记**：文件路径 + 改动位置 + 意图 + 对应迁移/任务。
> 上游合并窗口（每季度评估，安全修复立即合并）时按本清单逐条 reapply。

| # | 文件 | 改动位置 | 意图 | 来源任务 | 提交 |
|---|------|---------|------|---------|------|
| 1 | `crabc-api/crabc-core/.../filter/AuthInterceptor.java` | authType switch default 分支 | 未知认证类型**拒绝**（原放行） | 安全改造 #2 | 待填 |
| 2 | `crabc-api/crabc-core/.../util/JwtUtil.java` | secret 来源 | 硬编码 → 配置化 + 启动强校验 | 安全改造 #1 | 待填 |
| 3 | `crabc-api/crabc-core/.../core/impl/BaseDataServiceImpl.java` | execute 类型分派 default 分支 | 非 SELECT **拒绝**（原当查询执行） | 安全改造 #3 | 待填 |
| 4 | `crabc-api/crabc-core/.../util/SysUserController.java`（密码校验/存储） | 密码哈希 | MD5 → PBKDF2（兼容历史 admin 账号首次登录迁移） | 安全改造 #4 | 待填 |
| 5 | `crabc-api/crabc-core/.../core/impl/BaseGroupServiceImpl.java` | 非法操作校验 | 创建 RuntimeException 后 **throw**（原未抛出） | 安全改造 #5 | 待填 |
| 6 | `crabc-api/crabc-core/.../config/JwtInterceptor.java`（claims 组装） | 登录态上下文 | claims 增加 tenantId（M1 默认 `default`） | 租户化第一步 | 待填 |
| 7 | `crabc-api/crabc-datasource/...`（如需） | 执行入口接 SqlGuard | 创作时/运行时一道防线 | SqlGuard | 待填 |
| 8 | `db/mysql.sql` | 冻结 | V1 之后 schema 演进一律走 `db/flyway/`，本文件不再修改 | Flyway | 待填 |

> 登记纪律：提交核心文件改动时，同一次 commit 内更新本表（"提交"列填 commit 短哈希）。
