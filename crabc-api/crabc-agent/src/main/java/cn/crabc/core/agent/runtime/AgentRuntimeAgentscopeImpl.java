package cn.crabc.core.agent.runtime;

import cn.crabc.core.agent.context.TenantCtx;
import cn.crabc.core.agent.tool.ChatAgentTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * AgentRuntime 的 AgentScope Java 2.0 实现（chatView docs/05 §4）
 *
 * 【防腐层边界】本类是全工程唯一允许 import io.agentscope.* 的地方。
 * 装配原则：HarnessAgent 最小装配——只开 stateStore / compaction / toolResultEviction 需要的默认能力，
 * 不开 subagent / filesystem / sandbox / plan-mode（docs/05 §4.1）。
 *
 * 会话持久化：2.0 的 AgentState 按 (userId, sessionId) 自动加载/写回（默认文件实现，
 * MySQL 版 AgentStateStore 在 M1.5 替换——builder 已预留 .stateStore(...)）。
 *
 * @author chatview
 */
@Component
public class AgentRuntimeAgentscopeImpl implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeAgentscopeImpl.class);

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 模型串："dashscope:qwen-plus" / "deepseek:deepseek-chat" / "openai:..." / "ollama:..." */
    @Value("${crabc.agent.llm.model:dashscope:qwen-plus}")
    private String model;

    @Value("${crabc.agent.llm.sys-prompt:}")
    private String sysPromptOverride;

    private final ChatAgentTools tools;

    private volatile HarnessAgent agent;

    public AgentRuntimeAgentscopeImpl(ChatAgentTools tools) {
        this.tools = tools;
    }

    /** 系统提示词极简（DataAgent 验证的设计）：知识获取全部工具驱动，只放流程约束与日期时区 */
    private String sysPrompt() {
        if (sysPromptOverride != null && !sysPromptOverride.isBlank()) {
            return sysPromptOverride;
        }
        String now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DATETIME);
        return """
                你是 chatView 的看板搭建助手。当前时间：%s（Asia/Shanghai）。

                流程约束：
                1. 生成 SQL 前必须先用 get_domains 选域，再 get_tables 看表，再 get_table_schema 看列；禁止凭空猜测表名与列名。
                2. 只生成单条 SELECT；值一律用 #{命名参数}；禁止 ${}；禁止 DDL/DML。
                3. 执行前调用 execute_sql_preview 验证；SQL 报错会原样回给你，请自行修正（最多重试 2 次）。
                4. 口径模糊（如"订单量"是否排除已取消、按下单时间还是支付时间）必须调用 ask_user 追问，宁可多一轮，不要猜测。
                5. 数据确认后再用 generate_chart 产出图表 DSL，最后保存由用户点击触发。
                """.formatted(now);
    }

    /** 懒构建：模型供应商密钥由 ModelRegistry 从环境变量读取（DASHSCOPE_API_KEY / DEEPSEEK_API_KEY 等） */
    private HarnessAgent agent() {
        HarnessAgent local = agent;
        if (local == null) {
            synchronized (this) {
                if (agent == null) {
                    Toolkit toolkit = new Toolkit();
                    toolkit.registerTool(tools);
                    local = HarnessAgent.builder()
                            .name("chatview-agent")
                            .sysPrompt(sysPrompt())
                            .model(model)
                            .toolkit(toolkit)
                            .compaction(CompactionConfig.builder()
                                    .triggerMessages(40)
                                    .keepMessages(12)
                                    .build())
                            .build();
                    agent = local;
                    log.info("[chatview] HarnessAgent 就绪，model={}，tools={}", model, toolkit.getToolNames());
                }
            }
        }
        return local;
    }

    private RuntimeContext runtimeCtx(String sessionId, TenantCtx ctx) {
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(ctx.getUserId() == null ? "0" : String.valueOf(ctx.getUserId()))
                .put(TenantCtx.class, ctx)
                .build();
    }

    @Override
    public Flux<ChatEvent> chat(String sessionId, TenantCtx ctx, String userMessage) {
        return toChatEvents(agent().streamEvents(new UserMessage(userMessage), runtimeCtx(sessionId, ctx)));
    }

    @Override
    public Flux<ChatEvent> resume(String sessionId, TenantCtx ctx, String toolCallId, String toolName, String answer) {
        // TODO(M1.5)：外部工具结果回传走 Toolkit 的挂起恢复协议（RequireExternalExecutionEvent 对应 API），
        // 首版先以普通消息续跑（把答案作为用户消息注入），行为兼容、协议后续切到标准恢复。
        String continueMsg = "（追问回答）" + answer;
        return toChatEvents(agent().streamEvents(new UserMessage(continueMsg), runtimeCtx(sessionId, ctx)));
    }

    /** AgentEvent → ChatEvent 映射（唯一一处框架事件耦合） */
    private Flux<ChatEvent> toChatEvents(Flux<AgentEvent> events) {
        return events.map(event -> {
            AgentEventType type = event.getType();
            if (type == AgentEventType.TEXT_BLOCK_DELTA && event instanceof TextBlockDeltaEvent delta) {
                return ChatEvent.text(delta.getDelta());
            }
            if (type == AgentEventType.TOOL_CALL_START && event instanceof ToolCallStartEvent toolCall) {
                return ChatEvent.tool(toolCall.getToolCallName());
            }
            if (type == AgentEventType.THINKING_BLOCK_DELTA) {
                return new ChatEvent(ChatEvent.THINKING, String.valueOf(event));
            }
            if (type == AgentEventType.AGENT_RESULT && event instanceof io.agentscope.core.event.AgentResultEvent resultEvent) {
                Msg result = resultEvent.getResult();
                return ChatEvent.done(result == null ? null : result.getTextContent());
            }
            return new ChatEvent(type.name(), String.valueOf(event));
        });
    }
}
