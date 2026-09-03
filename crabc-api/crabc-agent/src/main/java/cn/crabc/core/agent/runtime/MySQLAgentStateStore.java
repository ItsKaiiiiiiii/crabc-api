package cn.crabc.core.agent.runtime;

import cn.crabc.core.agent.mapper.AgentStateMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentScope AgentStateStore 的 MySQL 实现（chatView/docs/05 §4.2）
 *
 * 替换默认 JsonFileAgentStateStore（本地文件仅适用单机开发），使会话状态进 MySQL：
 * 部署面保持"应用 + MySQL + 业务库"，多副本/重启恢复天然可用。
 * 序列化复用框架自己的 JsonCodec（与文件实现同源，保证 Msg 等复杂结构 round-trip 一致）。
 *
 * @author chatview
 */
@Component
public class MySQLAgentStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(MySQLAgentStateStore.class);

    @Autowired
    private AgentStateMapper agentStateMapper;

    private final JsonCodec jsonCodec = JsonUtils.getJsonCodec();

    /** stateName 约定 = State 类 SimpleName（与文件实现的命名对齐） */
    private static String nameOf(Class<?> clazz) {
        return clazz.getSimpleName();
    }

    @Override
    public void save(String agentId, String userId, String sessionId, State state) {
        agentStateMapper.upsert(agentId, userId, sessionId, nameOf(state.getClass()), 0,
                jsonCodec.toJson(state));
    }

    @Override
    public void save(String agentId, String userId, String sessionId, List<? extends State> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        String stateName = nameOf(states.get(0).getClass());
        agentStateMapper.deleteByState(agentId, userId, sessionId, stateName);
        int ordinal = 0;
        for (State state : states) {
            agentStateMapper.upsert(agentId, userId, sessionId, stateName, ordinal++, jsonCodec.toJson(state));
        }
    }

    @Override
    public <T extends State> Optional<T> get(String agentId, String userId, String sessionId, Class<T> clazz) {
        List<String> contents = agentStateMapper.selectContents(agentId, userId, sessionId, nameOf(clazz));
        if (contents == null || contents.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jsonCodec.fromJson(contents.get(0), clazz));
    }

    @Override
    public <T extends State> List<T> getList(String agentId, String userId, String sessionId, Class<T> clazz) {
        List<String> contents = agentStateMapper.selectContents(agentId, userId, sessionId, nameOf(clazz));
        List<T> result = new ArrayList<>();
        if (contents != null) {
            for (String content : contents) {
                result.add(jsonCodec.fromJson(content, clazz));
            }
        }
        return result;
    }

    @Override
    public boolean exists(String agentId, String sessionId) {
        return agentStateMapper.existsBySession(agentId, sessionId) > 0;
    }

    @Override
    public void delete(String agentId, String sessionId) {
        agentStateMapper.deleteBySession(agentId, sessionId);
    }

    @Override
    public Set<String> listSessionIds(String agentId) {
        return Set.copyOf(agentStateMapper.selectSessionIds(agentId));
    }
}
