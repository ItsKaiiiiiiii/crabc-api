package cn.crabc.core.agent.tool;

import cn.crabc.core.agent.context.TenantCtx;
import cn.crabc.core.agent.semantic.SemanticQueryService;
import cn.crabc.core.app.guard.GuardReport;
import cn.crabc.core.app.guard.SqlGuard;
import cn.crabc.core.app.service.core.IBaseDataService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * chatView Agent 工具集（chatView/docs/05 §4.4，6 个 M1 工具）
 *
 * 设计约定：
 * - 全部只经 SemanticQueryService 读语义层（租户过滤 + 权限剔除唯一入口）；
 * - 数据源一律经 TenantCtx（会话绑定），禁止"第一个 ACTIVE 源"式默认（修 DataAgent 弱点 7）；
 * - 工具描述文本即 prompt 的一部分：execute_sql_preview 描述中写明流程约束（提示层软约束），
 *   与 SqlGuard（执行层硬约束）构成双层防线（DataAgent 验证的模式）。
 *
 * @author chatview
 */
@Component
public class ChatAgentTools {

    private static final Logger log = LoggerFactory.getLogger(ChatAgentTools.class);

    @Autowired
    private SemanticQueryService semanticQueryService;

    @Autowired
    private IBaseDataService baseDataService;

    @Autowired
    private JsonMapper jsonMapper;

    @Tool(name = "get_domains", description = "获取当前租户的全部业务域（名称+描述+表数量）。这是选表的第一步：先读域列表，判断问题属于哪个域。")
    public String getDomains(TenantCtx ctx) {
        return toJson(semanticQueryService.getDomains(ctx.getTenantId()));
    }

    @Tool(name = "get_tables", description = "按业务域获取表卡片（表名+业务说明+行数）与表间关系。domains 为逗号分隔的域名称（必填，禁止为空）。若返回 truncated=true 说明已裁剪，请缩小域范围。")
    public String getTables(TenantCtx ctx,
                            @ToolParam(name = "domains", required = true, description = "逗号分隔的域名称，例如：交易,用户")
                            String domains) {
        if (domains == null || domains.isBlank()) {
            // 修 DataAgent 弱点 5：空参全量吐出——明确拒绝引导模型选域
            return "错误：domains 不能为空，请先根据 get_domains 的结果选择 1-3 个业务域";
        }
        List<String> domainNames = List.of(domains.split("[,，]"))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return toJson(semanticQueryService.getTableCards(ctx.getTenantId(), ctx.getDatasourceId(), domainNames));
    }

    @Tool(name = "get_table_schema", description = "获取单表的列明细（类型/主外键/业务说明/枚举值域）。生成 SQL 前必须先查看所用表的 schema；值域中的枚举映射（如 3=已取消）可直接用于过滤条件。")
    public String getTableSchema(TenantCtx ctx,
                                 @ToolParam(name = "table_name", required = true, description = "表名")
                                 String tableName) {
        return semanticQueryService.getTableSchema(ctx.getTenantId(), ctx.getDatasourceId(), tableName);
    }

    @Tool(name = "get_metric_caliber", description = "按业务词查询指标口径（如 GMV、有效订单）。命中即返回公式+过滤条件+时间列，可直接作为生成 SQL 的依据；多候选时需要与用户确认。")
    public String getMetricCaliber(TenantCtx ctx,
                                   @ToolParam(name = "hint", required = true, description = "业务词，例如：有效订单")
                                   String hint) {
        return toJson(semanticQueryService.getMetricCaliber(ctx.getTenantId(), ctx.getDatasourceId(), hint));
    }

    @Tool(name = "ask_user", description = "向用户追问以澄清模糊口径（例如：'订单量'是否排除已取消？按下单时间还是支付时间？）。口径不确定时必须调用本工具，禁止猜测。调用后本轮暂停，等待用户回答。",
            externalTool = true, readOnly = true)
    public String askUser(TenantCtx ctx,
                          @ToolParam(name = "question", required = true, description = "面向业务用户的、不带术语的追问")
                          String question) {
        // externalTool：框架发出 RequireExternalExecutionEvent 并暂停，本方法体正常情况下不会执行
        return question;
    }

    @Tool(name = "execute_sql_preview", description = "执行 SELECT 并返回数据预览（前若干行）。硬约束：单条 SELECT；值用 #{命名参数}；只能访问 get_table_schema 看过的表；执行前必须已经 get_table_schema。语法/白名单错误会原样返回给你修正（最多重试 2 次）。")
    public String executeSqlPreview(TenantCtx ctx,
                                    @ToolParam(name = "sql", required = true, description = "单条 SELECT 语句，值用 #{命名参数}")
                                    String sql,
                                    @ToolParam(name = "params", required = false, description = "命名参数的 JSON 对象，例如 {\"sttus\": 3}")
                                    String paramsJson) {
        // ② 道闸：租户授权表白名单（语义层已同步的表；未同步时放开并告警，M2 接 RBAC 后收紧）
        List<String> whitelist = semanticQueryService.getWhitelistedTables(ctx.getTenantId(), ctx.getDatasourceId());
        SqlGuard.AgentOptions options = whitelist.isEmpty()
                ? new SqlGuard.AgentOptions()
                : SqlGuard.AgentOptions.of(new java.util.HashSet<>(whitelist));
        GuardReport report = SqlGuard.getInstance().checkAgentQuery(sql, options);
        if (!report.allPassed()) {
            // 错误回喂模型自愈（DataAgent 错误分层自愈模式）
            return "SQL 被护栏拦截[" + report.firstRejectedGate() + "]：" + report.firstRejectMessage() + "，请修正后重试";
        }
        Map<String, Object> params = parseParams(paramsJson);
        try {
            Object result = baseDataService.execute(ctx.getDatasourceId(), null, null,
                    report.getSandboxedSql() != null ? report.getSandboxedSql() : sql, params);
            if (result instanceof List rows) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("row_count", rows.size());
                out.put("rows", rows.subList(0, Math.min(rows.size(), 20)));
                out.put("note", rows.size() > 20 ? "仅展示前 20 行" : null);
                return toJson(out);
            }
            return "非查询结果：" + result;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[chatview] execute_sql_preview 执行失败: {}", msg);
            return "SQL 执行失败：" + msg + "，请根据错误信息修正 SQL（检查列名/表名/类型）";
        }
    }

    // ==================== 私有 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return jsonMapper.readValue(paramsJson, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return jsonMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
