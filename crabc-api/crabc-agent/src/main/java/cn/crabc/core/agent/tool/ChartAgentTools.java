package cn.crabc.core.agent.tool;

import cn.crabc.core.agent.context.TenantCtx;
import cn.crabc.core.agent.mapper.ArtifactMapper;
import cn.crabc.core.agent.semantic.SemanticQueryService;
import cn.crabc.core.app.guard.GuardReport;
import cn.crabc.core.app.guard.SqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图表产物工具（chatView/docs/05 §4.4 / §10；P2 决策：SQL 定稿前在 Agent 内生成草案，保存走确定性管线）
 *
 * generate_chart：由扁平参数组装 DSL（LLM 友好 + 服务端组装保证 schema 合法），
 *                 校验通过返回 DSL——模型与前端都以此为准。
 * save_chart：   复跑护栏 → 固化 sql_artifact + chart_artifact → 三路回流（few-shot / eval_case / 语义沉淀暂缓）。
 *
 * @author chatview
 */
@Component
public class ChartAgentTools {

    private static final Logger log = LoggerFactory.getLogger(ChartAgentTools.class);

    private static final Set<String> CHART_TYPES = Set.of("bar", "line", "pie", "table", "metric");

    @Autowired
    private SemanticQueryService semanticQueryService;

    @Autowired
    private ArtifactMapper artifactMapper;

    /** DSL 服务端组装（模型只给扁平参数，编码类型推断写回 DSL，运行时零推断） */
    @Autowired
    private ChartDslAssembler chartDslAssembler;

    @Autowired
    private tools.jackson.databind.json.JsonMapper jsonMapper;

    @io.agentscope.core.tool.Tool(name = "generate_chart", description =
            "根据已确认的数据生成图表 DSL 草案。chart_type 可选：bar/line/pie/table/metric（数值卡）。"
                    + "x_field/y_field/series_field 必须是已执行 SQL 结果中的列名。返回的 DSL 即预览结果。")
    public String generateChart(TenantCtx ctx,
                                @io.agentscope.core.tool.ToolParam(name = "chart_type", required = true,
                                        description = "图表类型：bar/line/pie/table/metric") String chartType,
                                @io.agentscope.core.tool.ToolParam(name = "title", required = true,
                                        description = "图表标题，含口径说明") String title,
                                @io.agentscope.core.tool.ToolParam(name = "x_field", required = false,
                                        description = "X 轴字段（表格类型可空）") String xField,
                                @io.agentscope.core.tool.ToolParam(name = "y_field", required = false,
                                        description = "Y 轴/度量字段（表格类型可空）") String yField,
                                @io.agentscope.core.tool.ToolParam(name = "series_field", required = false,
                                        description = "系列分组字段（可选）") String seriesField) {
        Map<String, Object> dsl = chartDslAssembler.assemble(chartType, title, xField, yField, seriesField);
        if (dsl.containsKey("error")) {
            return "DSL 组装失败：" + dsl.get("error");
        }
        return jsonMapper.writeValueAsString(dsl);
    }

    @io.agentscope.core.tool.Tool(name = "save_chart", description =
            "保存图表产物（用户确认后调用）。会固化 SQL 与图表 DSL，并沉淀为租户的 few-shot 样例与评测集。"
                    + "参数与 generate_chart 一致，另需提供定稿的 sql 与 params。")
    public String saveChart(TenantCtx ctx,
                            @io.agentscope.core.tool.ToolParam(name = "sql", required = true,
                                    description = "定稿的单条 SELECT") String sql,
                            @io.agentscope.core.tool.ToolParam(name = "params", required = false,
                                    description = "命名参数 JSON 对象") String paramsJson,
                            @io.agentscope.core.tool.ToolParam(name = "chart_type", required = true,
                                    description = "图表类型：bar/line/pie/table/metric") String chartType,
                            @io.agentscope.core.tool.ToolParam(name = "title", required = true,
                                    description = "图表标题") String title,
                            @io.agentscope.core.tool.ToolParam(name = "x_field", required = false,
                                    description = "X 轴字段") String xField,
                            @io.agentscope.core.tool.ToolParam(name = "y_field", required = false,
                                    description = "Y 轴字段") String yField,
                            @io.agentscope.core.tool.ToolParam(name = "series_field", required = false,
                                    description = "系列字段") String seriesField) {
        // 保存走确定性管线：护栏复跑（④ 参数留痕）
        List<String> whitelist = semanticQueryService.getWhitelistedTables(ctx.getTenantId(), ctx.getDatasourceId());
        SqlGuard.AgentOptions options = whitelist.isEmpty()
                ? new SqlGuard.AgentOptions()
                : SqlGuard.AgentOptions.of(new java.util.HashSet<>(whitelist));
        GuardReport report = SqlGuard.getInstance().checkAgentQuery(sql, options);
        if (!report.allPassed()) {
            return "保存被护栏拦截[" + report.firstRejectedGate() + "]：" + report.firstRejectMessage();
        }

        Map<String, Object> dsl = chartDslAssembler.assemble(chartType, title, xField, yField, seriesField);
        if (dsl.containsKey("error")) {
            return "DSL 组装失败：" + dsl.get("error");
        }
        try {
            String tenantId = ctx.getTenantId();
            artifactMapper.insertSqlArtifact(tenantId, ctx.getSessionId(), ctx.getDatasourceId(),
                    sql, paramsJson == null ? "{}" : paramsJson, jsonMapper.writeValueAsString(report.getTables()));
            Long sqlArtifactId = artifactMapper.selectLatestSqlArtifactId(tenantId);
            artifactMapper.insertChartArtifact(tenantId, sqlArtifactId, title,
                    jsonMapper.writeValueAsString(dsl), null);

            // 三路回流（docs/05 §5.3.7）：few-shot 池 + 评测集（语义沉淀配确认 UX，M1.5）
            Long sessionId = ctx.getSessionId() == null ? null : Long.valueOf(ctx.getSessionId());
            String question = sessionId == null ? null : artifactMapper.selectLatestUserMessage(sessionId);
            if (question != null && !question.isBlank()) {
                artifactMapper.insertFewShot(tenantId, ctx.getDatasourceId(), question, sql);
                artifactMapper.insertEvalCase(tenantId, ctx.getDatasourceId(), question, sql);
            }

            Long chartId = artifactMapper.selectLatestChartArtifactId(tenantId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("chart_artifact_id", chartId);
            out.put("sql_artifact_id", sqlArtifactId);
            out.put("dsl", dsl);
            out.put("message", "已保存并可挂载到 overview 页");
            return jsonMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("[chatview] save_chart 失败: {}", e.getMessage());
            return "保存失败：" + e.getMessage();
        }
    }
}
