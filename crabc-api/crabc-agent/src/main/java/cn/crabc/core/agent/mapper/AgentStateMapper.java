package cn.crabc.core.agent.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AgentScope AgentStateStore 的 MySQL 存取（chatView/docs/05 §4.2）
 *
 * 寻址：(agentId, userId, sessionId) + stateName（State 类 SimpleName，对应文件实现的名字约定）
 *
 * @author chatview
 */
public interface AgentStateMapper {

    @Insert("INSERT INTO agent_state (agent_id, user_id, session_id, state_name, ordinal, content)"
            + " VALUES (#{agentId}, #{userId}, #{sessionId}, #{stateName}, #{ordinal}, #{content})"
            + " ON DUPLICATE KEY UPDATE content = VALUES(content), update_time = NOW()")
    int upsert(@Param("agentId") String agentId, @Param("userId") String userId,
               @Param("sessionId") String sessionId, @Param("stateName") String stateName,
               @Param("ordinal") int ordinal, @Param("content") String content);

    @Select("SELECT content FROM agent_state WHERE agent_id = #{agentId} AND user_id = #{userId}"
            + " AND session_id = #{sessionId} AND state_name = #{stateName} ORDER BY ordinal")
    List<String> selectContents(@Param("agentId") String agentId, @Param("userId") String userId,
                                @Param("sessionId") String sessionId, @Param("stateName") String stateName);

    @Delete("DELETE FROM agent_state WHERE agent_id = #{agentId} AND user_id = #{userId}"
            + " AND session_id = #{sessionId} AND state_name = #{stateName}")
    int deleteByState(@Param("agentId") String agentId, @Param("userId") String userId,
                      @Param("sessionId") String sessionId, @Param("stateName") String stateName);

    @Select("SELECT COUNT(1) FROM agent_state WHERE agent_id = #{agentId} AND session_id = #{sessionId}")
    int existsBySession(@Param("agentId") String agentId, @Param("sessionId") String sessionId);

    @Delete("DELETE FROM agent_state WHERE agent_id = #{agentId} AND session_id = #{sessionId}")
    int deleteBySession(@Param("agentId") String agentId, @Param("sessionId") String sessionId);

    @Select("SELECT DISTINCT session_id FROM agent_state WHERE agent_id = #{agentId}")
    List<String> selectSessionIds(@Param("agentId") String agentId);
}
