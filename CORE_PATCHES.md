# CORE_PATCHES — 核心文件改动登记

> 工程纪律（chatView/docs/05 §3.3 与 docs/04 §6.2）：fork 二开**优先新增**（crabc-agent 模块/新表/新配置），少改核心。
> **凡改动上游已有文件，必须在本文档登记**：文件路径 + 改动位置 + 意图 + 提交哈希。
> 上游合并窗口（每季度评估，upstream 安全修复立即合并）时按本清单逐条 reapply。
> 上游：`git remote add upstream git@github.com:pingapi/crabc-api.git`

## chatView 首批改造（feature/chatview，2026-09-03）

| # | 文件 | 改动位置 | 意图 | 提交 |
|---|------|---------|------|------|
| 1 | `crabc-core/.../util/JwtUtil.java` | secret 静态初始化 → `configure()` 注入；新增 tenantId claim / refresh token / token 类型 | 安全#1 #6 #7 | be50f45 |
| 2 | `crabc-core/.../config/JwtSecurityProperties.java` | **新增文件**：secret 启动强校验（HS256 ≥32 字节），缺失拒绝启动 | 安全#1 | be50f45 |
| 3 | `crabc-core/.../filter/AuthInterceptor.java` | authType switch default 分支放行 → 拒绝 | 安全#2 | be50f45 |
| 4 | `crabc-core/.../service/core/impl/BaseDataServiceImpl.java` | execute 类型分派：default 分支（DDL 当查询执行）→ 显式拒绝；select 分支接 `SqlGuard.guardQueryOrThrow` | 安全#3 + 护栏接线 | be50f45 |
| 5 | `crabc-core/.../controller/SysUserController.java` | 登录/改密/注册接 `PwdCodec`（PBKDF2，存量 MD5 透明升级）；refresh_token 真实现；新增 `/refresh` 端点 | 安全#4 #7 | be50f45 |
| 6 | `crabc-core/.../util/PwdCodec.java` | **新增文件**：PBKDF2WithHmacSHA256（60 万次迭代），双格式校验 | 安全#4 | be50f45 |
| 7 | `crabc-core/.../service/system/impl/BaseGroupServiceImpl.java` | 4 处 `new RuntimeException` 未 throw → throw | 安全#5 | be50f45 |
| 8 | `crabc-core/.../util/UserThreadLocal.java` | 新增 `getTenantId()`（JWT claims，缺省 default） | 租户第一步 | be50f45 |
| 9 | `crabc-core/.../entity/param/UserParam.java` | 新增 `refreshToken` 字段 | 安全#7 | be50f45 |
| 10 | `crabc-datasource/.../exception/CustomException.java` | 构造器补 `super(message)`（原 getMessage() 恒 null） | 日志可排查性 | be50f45 |
| 11 | `crabc-core/.../config/DataSourceConfig.java` | `defaultDataSource()` 加 `@DependsOn("chatViewFlyway")`（迁移先行） | Flyway | be50f45 |
| 12 | `crabc-core/.../config/ChatViewFlywayConfig.java` | **新增文件**：Flyway 独立直连系统库（绝不经路由数据源），baselineOnMigrate 兼容存量库 | Flyway | be50f45 |
| 13 | `crabc-core/.../config/InterceptorConfig.java` | `/api/v1/**` 接入 JWT 拦截；`/api/box/sys/user/refresh` 放行 | 会话接口 | 本次 |
| 14 | `crabc-admin/.../application.yml` | 追加 `crabc.*` 配置块（jwt/flyway/agent） | 配置 | 本次 |

## 新增（非补丁，无需 reapply）

| 内容 | 位置 |
|------|------|
| crabc-agent Maven 模块（agentscope-harness 2.0.1，包名 `cn.crabc.core.agent` 适配既有 ComponentScan/MapperScan 通配符） | `crabc-api/crabc-agent/` |
| Flyway 迁移 V1 基线 / V2 租户字段 / V3 语义层+会话+产物+评测 | `crabc-core/src/main/resources/db/flyway/` |
| SqlGuard 四道闸（含 40 条单测，全过） | `crabc-core/.../guard/` |
| 语义层（SemanticQueryService 权限剔除唯一入口 / SchemaSyncService 含值域 profiling） | `crabc-agent/.../semantic/` |
| Agent 工具集 6 个（get_domains/get_tables/get_table_schema/get_metric_caliber/ask_user/execute_sql_preview） | `crabc-agent/.../tool/` |
| SSE 会话接口（/api/v1/sessions） | `crabc-agent/.../web/` |
| 依赖：flyway-core/mysql 11.7.2、jsqlparser 4.9、agentscope-harness 2.0.1 | 根 pom `chatView` 属性块 |

## 纪律提醒

1. `db/mysql.sql` 自 V1 基线起**冻结**，schema 演进只新增 `db/flyway/V*.sql`
2. upstream 合并窗口前 `git fetch upstream`，按本表逐条确认补丁是否仍需要（上游可能已修同类问题）
3. 新增功能一律放 `cn.crabc.core.agent` 包或新模块，避免触碰本表
