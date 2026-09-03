package cn.crabc.core.agent.semantic;

import cn.crabc.core.agent.mapper.SemanticMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语义层查询服务（chatView/docs/05 §3.4 / §5）
 *
 * 【权限剔除唯一入口】所有 Agent 工具只经本服务读语义层：
 * 租户过滤（tenant_id 硬条件）+ hidden 列剔除都在这里完成，一处实现才可能被测试覆盖。
 * 无向量（R8）：域推理 → 表卡片（含双向关系边）→ 列明细（含值域陈列）。
 *
 * @author chatview
 */
@Service
public class SemanticQueryService {

    private static final Logger log = LoggerFactory.getLogger(SemanticQueryService.class);

    @Autowired
    private SemanticMapper semanticMapper;

    @Autowired
    private JsonMapper jsonMapper;

    /** R8 第二级兜底阈值：单域表数超过即裁剪（P1 默认 50，埋点校准） */
    @Value("${crabc.agent.recall.domain-table-threshold:50}")
    private int domainTableThreshold;

    /** 域列表（LLM 选域依据） */
    public List<Map<String, Object>> getDomains(String tenantId) {
        return semanticMapper.selectDomains(tenantId);
    }

    /** 域内表卡片 + 双向关系边；超阈值时确定性裁剪并标记 truncated */
    public Map<String, Object> getTableCards(String tenantId, String datasourceId, List<String> domainNames) {
        List<Long> domainIds = new ArrayList<>();
        if (domainNames != null && !domainNames.isEmpty()) {
            for (Map<String, Object> d : getDomains(tenantId)) {
                if (domainNames.contains(String.valueOf(d.get("name")))) {
                    domainIds.add(((Number) d.get("id")).longValue());
                }
            }
        }
        List<Map<String, Object>> tables = semanticMapper.selectTables(tenantId, datasourceId, domainIds);
        boolean truncated = false;
        if (tables.size() > domainTableThreshold) {
            // R8 第二级兜底（零向量起步）：确定性排序裁剪，BM25/FULLTEXT 版本在表数验证后切换
            tables = tables.stream()
                    .sorted((a, b) -> {
                        int byDesc = Boolean.compare(b.get("description") != null, a.get("description") != null);
                        if (byDesc != 0) {
                            return byDesc;
                        }
                        return Long.compare(rowCount(b), rowCount(a));
                    })
                    .limit(domainTableThreshold)
                    .collect(Collectors.toList());
            truncated = true;
        }
        List<Long> tableIds = tables.stream().map(t -> ((Number) t.get("id")).longValue()).collect(Collectors.toList());
        List<Map<String, Object>> relations = semanticMapper.selectRelations(tenantId, tableIds);
        return Map.of("tables", tables, "relations", relations, "truncated", truncated);
    }

    /**
     * 表卡片：列明细 + 主外键 + 值域陈列（R1）+ 人工口径；hidden 列不返回
     */
    public String getTableSchema(String tenantId, String datasourceId, String tableName) {
        Map<String, Object> table = semanticMapper.selectTable(tenantId, datasourceId, tableName);
        if (table == null) {
            return "表不存在或未同步：" + tableName + "（请先 get_tables 确认表名）";
        }
        Long tableId = ((Number) table.get("id")).longValue();
        List<Map<String, Object>> columns = semanticMapper.selectColumns(tenantId, tableId);
        StringBuilder sb = new StringBuilder();
        sb.append("表: ").append(tableName);
        if (table.get("description") != null) {
            sb.append(" | 说明: ").append(table.get("description"));
        }
        sb.append(" | 行数: ").append(table.get("row_count")).append('\n');
        sb.append("字段:\n");
        for (Map<String, Object> col : columns) {
            sb.append("- ").append(col.get("column_name")).append(' ').append(col.get("data_type"));
            if (num(col.get("is_pk")) == 1) {
                sb.append(" [主键]");
            }
            if (num(col.get("is_fk")) == 1) {
                sb.append(" [外键→").append(col.get("fk_target")).append(']');
            }
            if (col.get("description") != null) {
                sb.append(" // ").append(col.get("description"));
            }
            String valueDomain = renderValueDomain(col.get("value_domain"), num(col.get("value_confirmed")) == 1);
            if (!valueDomain.isEmpty()) {
                sb.append(" 值域: ").append(valueDomain);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 租户授权表名单（SqlGuard ② 白名单来源） */
    public List<String> getWhitelistedTables(String tenantId, String datasourceId) {
        return semanticMapper.selectTableNames(tenantId, datasourceId);
    }

    /** 指标口径命中（四要素 + aliases） */
    public List<Map<String, Object>> getMetricCaliber(String tenantId, String datasourceId, String hint) {
        if (hint == null || hint.isBlank()) {
            return List.of();
        }
        return semanticMapper.selectMetrics(tenantId, datasourceId, hint.trim());
    }

    // ==================== 私有 ====================

    /** 值域陈列：[{"v":3,"label":"已取消"}] → "3=已取消, 1=待付款(待确认)" */
    @SuppressWarnings("unchecked")
    private String renderValueDomain(Object valueDomainJson, boolean confirmed) {
        if (valueDomainJson == null) {
            return "";
        }
        try {
            List<Map<String, Object>> items = jsonMapper.readValue(valueDomainJson.toString(), List.class);
            if (items == null || items.isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> item : items) {
                Object v = item.get("v");
                Object label = item.get("label");
                boolean c = Boolean.TRUE.equals(item.get("confirmed"));
                parts.add(v + (label == null ? "" : "=" + label) + (c ? "" : "(待确认)"));
            }
            return String.join(", ", parts) + (confirmed ? "" : "（含义为猜测，请以实际业务为准）");
        } catch (Exception e) {
            log.warn("值域渲染失败: {}", e.getMessage());
            return "";
        }
    }

    private long rowCount(Map<String, Object> table) {
        Object rc = table.get("row_count");
        return rc instanceof Number ? ((Number) rc).longValue() : 0L;
    }

    private int num(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
