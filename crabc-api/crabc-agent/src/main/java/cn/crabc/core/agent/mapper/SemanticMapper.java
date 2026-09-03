package cn.crabc.core.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 语义层 MyBatis 映射（注解式，零 XML）
 *
 * 租户过滤硬约束：所有 SQL 一律带 tenant_id（chatView/docs/05 §3.4）。
 * 同步 upsert 沿用 DataAgent 验证过的策略：physical_* 随同步覆盖；
 * 人工口径 business_description 用 COALESCE(NULLIF(...)) 保证永不被物理注释覆盖。
 *
 * @author chatview
 */
public interface SemanticMapper {

    // ==================== 域 ====================

    @Select("SELECT id, name, description FROM sem_domain WHERE tenant_id = #{tenantId} ORDER BY id")
    List<Map<String, Object>> selectDomains(@Param("tenantId") String tenantId);

    @Select("<script>SELECT st.id, st.table_name, COALESCE(NULLIF(st.business_description,''), st.physical_comment) AS description, st.row_count, st.domain_id"
            + " FROM sem_table st WHERE st.tenant_id = #{tenantId} AND st.datasource_id = #{datasourceId} AND st.physical_status = 1"
            + " <if test=\"domainIds != null and domainIds.size() > 0\"> AND st.domain_id IN <foreach item='d' collection='domainIds' open='(' separator=',' close=')'>#{d}</foreach></if>"
            + " ORDER BY st.id</script>")
    List<Map<String, Object>> selectTables(@Param("tenantId") String tenantId,
                                           @Param("datasourceId") String datasourceId,
                                           @Param("domainIds") List<Long> domainIds);

    // ==================== 表 ====================

    @Select("SELECT id, table_name, COALESCE(NULLIF(business_description,''), physical_comment) AS description, row_count, domain_id, schema_name"
            + " FROM sem_table WHERE tenant_id = #{tenantId} AND datasource_id = #{datasourceId} AND table_name = #{tableName} AND physical_status = 1")
    Map<String, Object> selectTable(@Param("tenantId") String tenantId,
                                    @Param("datasourceId") String datasourceId,
                                    @Param("tableName") String tableName);

    @Select("SELECT table_name FROM sem_table WHERE tenant_id = #{tenantId} AND datasource_id = #{datasourceId} AND physical_status = 1")
    List<String> selectTableNames(@Param("tenantId") String tenantId, @Param("datasourceId") String datasourceId);

    // ==================== 列 ====================

    @Select("SELECT column_name, data_type, is_pk, is_fk, fk_target,"
            + " COALESCE(NULLIF(business_description,''), physical_comment) AS description,"
            + " value_domain, value_confirmed"
            + " FROM sem_column WHERE tenant_id = #{tenantId} AND table_id = #{tableId} AND hidden = 0 ORDER BY id")
    List<Map<String, Object>> selectColumns(@Param("tenantId") String tenantId, @Param("tableId") Long tableId);

    // ==================== 关系（join 边双向可见，修 DataAgent 弱点 6） ====================

    @Select("<script>SELECT source_table_id, source_columns, target_table_id, target_columns, rel_type FROM sem_relation"
            + " WHERE tenant_id = #{tenantId}"
            + " <if test=\"tableIds != null and tableIds.size() > 0\"> AND (source_table_id IN <foreach item='t' collection='tableIds' open='(' separator=',' close=')'>#{t}</foreach>"
            + " OR target_table_id IN <foreach item='t' collection='tableIds' open='(' separator=',' close=')'>#{t}</foreach>)</if>"
            + "</script>")
    List<Map<String, Object>> selectRelations(@Param("tenantId") String tenantId, @Param("tableIds") List<Long> tableIds);

    // ==================== 指标（四要素 + aliases，LIKE 命中，零向量） ====================

    @Select("SELECT metric_key, name, description, measure_expr, filters, time_field, aliases FROM sem_metric"
            + " WHERE tenant_id = #{tenantId} AND datasource_id = #{datasourceId}"
            + " AND (name LIKE CONCAT('%',#{hint},'%') OR aliases LIKE CONCAT('%',#{hint},'%') OR metric_key LIKE CONCAT('%',#{hint},'%')"
            + " OR description LIKE CONCAT('%',#{hint},'%')) LIMIT 3")
    List<Map<String, Object>> selectMetrics(@Param("tenantId") String tenantId,
                                            @Param("datasourceId") String datasourceId,
                                            @Param("hint") String hint);

    // ==================== 同步 upsert（物理/人工分离） ====================

    @Insert("INSERT INTO sem_table (tenant_id, datasource_id, schema_name, table_name, physical_comment, physical_status, row_count)"
            + " VALUES (#{tenantId}, #{datasourceId}, #{schemaName}, #{tableName}, #{physicalComment}, 1, #{rowCount})"
            + " ON DUPLICATE KEY UPDATE physical_comment = VALUES(physical_comment), physical_status = 1, row_count = VALUES(row_count),"
            + " business_description = COALESCE(NULLIF(business_description,''), VALUES(physical_comment)), update_time = NOW()")
    int upsertTable(@Param("tenantId") String tenantId, @Param("datasourceId") String datasourceId,
                    @Param("schemaName") String schemaName, @Param("tableName") String tableName,
                    @Param("physicalComment") String physicalComment, @Param("rowCount") Long rowCount);

    @Insert("INSERT INTO sem_column (tenant_id, table_id, column_name, data_type, physical_comment)"
            + " VALUES (#{tenantId}, #{tableId}, #{columnName}, #{dataType}, #{physicalComment})"
            + " ON DUPLICATE KEY UPDATE data_type = VALUES(data_type), physical_comment = VALUES(physical_comment),"
            + " business_description = COALESCE(NULLIF(business_description,''), VALUES(physical_comment)), update_time = NOW()")
    int upsertColumn(@Param("tenantId") String tenantId, @Param("tableId") Long tableId,
                     @Param("columnName") String columnName, @Param("dataType") String dataType,
                     @Param("physicalComment") String physicalComment);

    @Update("UPDATE sem_column SET value_domain = #{valueDomain}, update_time = NOW()"
            + " WHERE tenant_id = #{tenantId} AND table_id = #{tableId} AND column_name = #{columnName} AND value_confirmed = 0")
    int updateValueDomain(@Param("tenantId") String tenantId, @Param("tableId") Long tableId,
                          @Param("columnName") String columnName, @Param("valueDomain") String valueDomain);

    /** 物理侧消失只置 0 不删记录（保留人工口径资产） */
    @Update("UPDATE sem_table SET physical_status = 0, update_time = NOW()"
            + " WHERE tenant_id = #{tenantId} AND datasource_id = #{datasourceId} AND table_name = #{tableName}")
    int markTableMissing(@Param("tenantId") String tenantId, @Param("datasourceId") String datasourceId,
                         @Param("tableName") String tableName);
}
