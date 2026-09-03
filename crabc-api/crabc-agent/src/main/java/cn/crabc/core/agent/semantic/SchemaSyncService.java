package cn.crabc.core.agent.semantic;

import cn.crabc.core.agent.mapper.SemanticMapper;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.spi.MetaDataMapper;
import cn.crabc.core.spi.bean.Column;
import cn.crabc.core.spi.bean.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 语义层同步服务（chatView/docs/05 §7 + R1 值域 profiling）
 *
 * 同步策略（DataAgent 验证的设计，代码自研）：
 * - 物理缓存（physical_*）随同步覆盖；人工口径 business_description 永不被覆盖；
 * - 物理侧消失只置 physical_status=0，不删记录；
 * - 值域 profiling：低基数字符串列采样 distinct 值入 value_domain（零向量陈列形态，R1）。
 *
 * @author chatview
 */
@Service
public class SchemaSyncService {

    private static final Logger log = LoggerFactory.getLogger(SchemaSyncService.class);

    /** 可推断值域的列类型关键词 */
    private static final Pattern LOW_CARD_TYPES = Pattern.compile("char|enum|text", Pattern.CASE_INSENSITIVE);
    /** 单表值域 profiling 列数上限（同步成本护栏） */
    private static final int VALUE_DOMAIN_COLUMNS_PER_TABLE = 10;
    /** 大表不做 distinct 采样（R1 风险登记：超大表 distinct 超时） */
    private static final long VALUE_DOMAIN_MAX_ROWS = 1_000_000L;

    @Autowired
    private DataSourceManager dataSourceManager;

    @Autowired
    private IBaseDataService baseDataService;

    @Autowired
    private SemanticMapper semanticMapper;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${crabc.agent.sync.value-domain-enabled:true}")
    private boolean valueDomainEnabled;

    /**
     * 同步一个数据源的结构 + 值域（M1 由管理端手动触发）
     *
     * @return 同步的表数量
     */
    public int syncDatasource(String tenantId, String datasourceId, String schemaName) {
        MetaDataMapper metaData = dataSourceManager.getMetaData(datasourceId);
        if (metaData == null) {
            throw new IllegalArgumentException("数据源不可用：" + datasourceId);
        }
        // MySQL 系 catalog=schema；PG 系 catalog=null schema=schema（兼容 crabc 的双语义）
        List<Table> tables = safeTables(metaData, datasourceId, schemaName);
        int count = 0;
        for (Table table : tables) {
            if (table.getTableName() == null) {
                continue;
            }
            String physicalComment = table.getRemarks();
            semanticMapper.upsertTable(tenantId, datasourceId, schemaName,
                    table.getTableName(), physicalComment, table.getRows());
            count++;
            syncColumns(metaData, tenantId, datasourceId, schemaName, table);
        }
        log.info("[chatview] 语义层同步完成 tenant={} datasource={} schema={} tables={}",
                tenantId, datasourceId, schemaName, count);
        return count;
    }

    private List<Table> safeTables(MetaDataMapper metaData, String datasourceId, String schemaName) {
        List<Table> tables = metaData.getTables(datasourceId, schemaName, null);
        if (tables == null || tables.isEmpty()) {
            tables = metaData.getTables(datasourceId, null, schemaName);
        }
        return tables == null ? List.of() : tables;
    }

    private void syncColumns(MetaDataMapper metaData, String tenantId, String datasourceId,
                             String schemaName, Table table) {
        List<Column> columns;
        try {
            columns = metaData.getColumns(datasourceId, table.getCatalog(), table.getSchema(), table.getTableName());
        } catch (Exception e) {
            columns = metaData.getColumns(datasourceId, schemaName, null, table.getTableName());
        }
        if (columns == null) {
            return;
        }
        Map<String, Object> semTable = semanticMapper.selectTable(tenantId, datasourceId, table.getTableName());
        if (semTable == null) {
            return;
        }
        Long tableId = ((Number) semTable.get("id")).longValue();
        int profiled = 0;
        for (Column column : columns) {
            if (column.getColumnName() == null) {
                continue;
            }
            semanticMapper.upsertColumn(tenantId, tableId, column.getColumnName(),
                    column.getColumnType(), column.getRemarks());
            // R1 值域 profiling：低基数字符串列 + 未确认 + 表不太大 + 每表限量
            if (valueDomainEnabled && profiled < VALUE_DOMAIN_COLUMNS_PER_TABLE
                    && column.getColumnType() != null && LOW_CARD_TYPES.matcher(column.getColumnType()).find()
                    && table.getRows() != null && table.getRows() < VALUE_DOMAIN_MAX_ROWS) {
                profileValueDomain(tenantId, tableId, datasourceId, schemaName, table.getTableName(), column);
                profiled++;
            }
        }
    }

    /** 低基数列 distinct 采样（SELECT DISTINCT ... LIMIT 51），失败静默跳过不阻塞同步 */
    private void profileValueDomain(String tenantId, Long tableId, String datasourceId,
                                    String schemaName, String tableName, Column column) {
        try {
            String quotedTable = (schemaName == null || schemaName.isBlank() ? "" : "`" + schemaName + "`.")
                    + "`" + tableName + "`";
            String sql = "SELECT DISTINCT `" + column.getColumnName() + "` AS v FROM " + quotedTable + " LIMIT 51";
            Object result = baseDataService.execute(datasourceId, null, schemaName, sql, new HashMap<>());
            if (!(result instanceof List)) {
                return;
            }
            Set<Object> values = new LinkedHashSet<>();
            for (Object row : (List<?>) result) {
                if (row instanceof Map) {
                    values.add(((Map<?, ?>) row).get("v"));
                }
                if (values.size() > 50) {
                    break;
                }
            }
            if (values.isEmpty() || values.size() > 50) {
                return; // 空列或基数过高：不建值域
            }
            List<Map<String, Object>> domain = new ArrayList<>();
            for (Object v : values) {
                domain.add(Map.of("v", String.valueOf(v)));
            }
            semanticMapper.updateValueDomain(tenantId, tableId, column.getColumnName(),
                    jsonMapper.writeValueAsString(domain));
        } catch (Exception e) {
            log.debug("值域采样跳过 {}.{}: {}", tableName, column.getColumnName(),
                    rootMessage(e));
        }
    }

    private String rootMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
    }
}
