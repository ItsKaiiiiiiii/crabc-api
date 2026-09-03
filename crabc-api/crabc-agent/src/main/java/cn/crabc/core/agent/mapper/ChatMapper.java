package cn.crabc.core.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 会话与消息持久化（chatView/docs/05 §7）
 *
 * 分工：AgentScope AgentState（对话记忆，框架经 stateStore 持久化）承载对话历史；
 * chat_session.slots 承载产品结构化状态（槽位）。两者不混。
 *
 * @author chatview
 */
public interface ChatMapper {

    @Insert("INSERT INTO chat_session (tenant_id, user_id, datasource_id, title, stage, slots)"
            + " VALUES (#{tenantId}, #{userId}, #{datasourceId}, #{title}, 'INIT', NULL)")
    int insertSession(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                      @Param("datasourceId") String datasourceId, @Param("title") String title);

    @Select("SELECT id FROM chat_session WHERE tenant_id = #{tenantId} AND user_id = #{userId}"
            + " ORDER BY id DESC LIMIT 1")
    Long selectLatestSessionId(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    @Select("SELECT id, tenant_id, user_id, datasource_id, title, stage, slots FROM chat_session"
            + " WHERE id = #{sessionId} AND tenant_id = #{tenantId}")
    Map<String, Object> selectSession(@Param("sessionId") Long sessionId, @Param("tenantId") String tenantId);

    @Insert("INSERT INTO chat_message (session_id, tenant_id, role, content)"
            + " VALUES (#{sessionId}, #{tenantId}, #{role}, #{content})")
    int insertMessage(@Param("sessionId") Long sessionId, @Param("tenantId") String tenantId,
                      @Param("role") String role, @Param("content") String content);
}
