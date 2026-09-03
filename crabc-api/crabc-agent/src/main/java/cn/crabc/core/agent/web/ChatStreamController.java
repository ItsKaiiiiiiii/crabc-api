package cn.crabc.core.agent.web;

import cn.crabc.core.agent.context.TenantCtx;
import cn.crabc.core.agent.mapper.ChatMapper;
import cn.crabc.core.agent.runtime.AgentRuntime;
import cn.crabc.core.agent.runtime.ChatEvent;
import cn.crabc.core.app.util.UserThreadLocal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 创作会话接口（chatView/docs/05 §8）
 *
 * 登录态：复用 crabc JWT（JwtInterceptor 已拦 /api/v1/**，见 InterceptorConfig），
 * 租户与用户从 JWT claims（UserThreadLocal）来。
 * 流式：AgentScope streamEvents → ChatEvent → SSE。
 *
 * @author chatview
 */
@RestController
@RequestMapping("/api/v1")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    /** 虚拟线程执行器（Spring Boot 4 全局虚拟线程已开，这里显式建池用于 SSE 订阅回调） */
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    private AgentRuntime agentRuntime;

    @Autowired
    private ChatMapper chatMapper;

    @Autowired
    private JsonMapper jsonMapper;

    /** 创建会话（指定业务数据源） */
    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, Object> body) {
        String tenantId = UserThreadLocal.getTenantId();
        Long userId = Long.valueOf(UserThreadLocal.getUserId());
        String datasourceId = String.valueOf(body.get("datasourceId"));
        String title = body.get("title") == null ? "新会话" : String.valueOf(body.get("title"));
        chatMapper.insertSession(tenantId, userId, datasourceId, title);
        Long sessionId = chatMapper.selectLatestSessionId(tenantId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        return result;
    }

    /** 发送消息（SSE 流式返回：text/tool_call/done/error） */
    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        String tenantId = UserThreadLocal.getTenantId();
        Long userId = Long.valueOf(UserThreadLocal.getUserId());
        String content = String.valueOf(body.getOrDefault("content", ""));

        Map<String, Object> session = chatMapper.selectSession(id, tenantId);
        if (session == null) {
            SseEmitter rejected = new SseEmitter();
            rejected.completeWithError(new IllegalArgumentException("会话不存在：" + id));
            return rejected;
        }
        String datasourceId = String.valueOf(session.get("datasource_id"));
        chatMapper.insertMessage(id, tenantId, "user", content);

        SseEmitter emitter = new SseEmitter(300_000L);
        TenantCtx ctx = new TenantCtx(tenantId, userId, String.valueOf(id), datasourceId);
        StringBuilder assistantText = new StringBuilder();

        agentRuntime.chat(String.valueOf(id), ctx, content)
                .subscribe(
                        event -> {
                            try {
                                if (ChatEvent.TEXT.equals(event.getType()) && event.getPayload() != null) {
                                    assistantText.append(event.getPayload());
                                }
                                emitter.send(SseEmitter.event().name(event.getType())
                                        .data(jsonMapper.writeValueAsString(event.getPayload())));
                            } catch (Exception e) {
                                log.debug("[chatview] SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event().name(ChatEvent.ERROR)
                                        .data(jsonMapper.writeValueAsString(String.valueOf(error.getMessage()))));
                            } catch (Exception ignored) {
                                // 客户端已断开
                            }
                            emitter.completeWithError(error);
                        },
                        () -> {
                            if (assistantText.length() > 0) {
                                chatMapper.insertMessage(id, tenantId, "assistant", assistantText.toString());
                            }
                            emitter.complete();
                        });
        return emitter;
    }

    /** 会话状态与槽位 */
    @GetMapping("/sessions/{id}")
    public Map<String, Object> getSession(@PathVariable("id") Long id) {
        String tenantId = UserThreadLocal.getTenantId();
        Map<String, Object> session = chatMapper.selectSession(id, tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (session == null) {
            result.put("exists", false);
            return result;
        }
        result.put("exists", true);
        result.put("sessionId", session.get("id"));
        result.put("stage", session.get("stage"));
        result.put("slots", session.get("slots"));
        result.put("datasourceId", session.get("datasource_id"));
        return result;
    }
}
