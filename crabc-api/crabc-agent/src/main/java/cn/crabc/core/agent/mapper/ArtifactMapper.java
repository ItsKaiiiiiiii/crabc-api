package cn.crabc.core.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 产物（SQL / 图表）与飞轮回流持久化（chatView/docs/05 §5.3.7 三路回流、§7）
 *
 * @author chatview
 */
public interface ArtifactMapper {

    @Insert("INSERT INTO sql_artifact (tenant_id, session_id, datasource_id, sql_text, params_def, version, guard_report, status)"
            + " VALUES (#{tenantId}, #{sessionId}, #{datasourceId}, #{sqlText}, #{paramsDef}, 1, #{guardReport}, 'confirmed')")
    int insertSqlArtifact(@Param("tenantId") String tenantId, @Param("sessionId") String sessionId,
                          @Param("datasourceId") String datasourceId, @Param("sqlText") String sqlText,
                          @Param("paramsDef") String paramsDef, @Param("guardReport") String guardReport);

    @Select("SELECT id FROM sql_artifact WHERE tenant_id = #{tenantId} ORDER BY id DESC LIMIT 1")
    Long selectLatestSqlArtifactId(@Param("tenantId") String tenantId);

    @Insert("INSERT INTO chart_artifact (tenant_id, sql_artifact_id, title, dsl, refresh)"
            + " VALUES (#{tenantId}, #{sqlArtifactId}, #{title}, #{dsl}, #{refresh})")
    int insertChartArtifact(@Param("tenantId") String tenantId, @Param("sqlArtifactId") Long sqlArtifactId,
                            @Param("title") String title, @Param("dsl") String dsl, @Param("refresh") String refresh);

    @Select("SELECT id FROM chart_artifact WHERE tenant_id = #{tenantId} ORDER BY id DESC LIMIT 1")
    Long selectLatestChartArtifactId(@Param("tenantId") String tenantId);

    // ==================== 飞轮回流（R4/R8：经验知识 + 评测集） ====================

    @Insert("INSERT INTO few_shot_pool (tenant_id, datasource_id, question, sql_text)"
            + " VALUES (#{tenantId}, #{datasourceId}, #{question}, #{sqlText})")
    int insertFewShot(@Param("tenantId") String tenantId, @Param("datasourceId") String datasourceId,
                      @Param("question") String question, @Param("sqlText") String sqlText);

    @Insert("INSERT INTO eval_case (tenant_id, datasource_id, question, gold_sql, source)"
            + " VALUES (#{tenantId}, #{datasourceId}, #{question}, #{goldSql}, 'feedback')")
    int insertEvalCase(@Param("tenantId") String tenantId, @Param("datasourceId") String datasourceId,
                       @Param("question") String question, @Param("goldSql") String goldSql);

    @Select("SELECT content FROM chat_message WHERE session_id = #{sessionId} AND role = 'user'"
            + " ORDER BY id DESC LIMIT 1")
    String selectLatestUserMessage(@Param("sessionId") Long sessionId);
}
