package cn.crabc.core.agent.runtime;

import cn.crabc.core.agent.context.TenantCtx;
import reactor.core.publisher.Flux;

/**
 * Agent 运行时防腐层（chatView docs/05 §4.5，R5）
 *
 * 本接口不出现任何 io.agentscope.* 类型——框架 API 断代（1.0 → 2.0）已被证实过一次，
 * 隔离的代价是每轮升级只动 AgentRuntimeAgentscopeImpl 一个类。
 * 退出成本已核算：若框架阻塞进展，自研 ReAct 循环约 1.5-2 周（工具数 ≤ 8、循环模式固定）。
 *
 * @author chatview
 */
public interface AgentRuntime {

    /**
     * 发起一轮创作对话（流式返回过程事件）
     *
     * @param sessionId  会话 ID（AgentScope 侧以此恢复记忆，stateStore 持久化）
     * @param ctx        租户上下文（透传到每次工具调用）
     * @param userMessage用户输入
     */
    Flux<ChatEvent> chat(String sessionId, TenantCtx ctx, String userMessage);

    /**
     * ask_user 挂起后，用户答案回传续跑（外部工具结果注入）
     *
     * @param sessionId   会话 ID
     * @param ctx         租户上下文
     * @param toolCallId  被挂起的工具调用 ID
     * @param toolName    工具名（ask_user）
     * @param answer      用户回答
     */
    Flux<ChatEvent> resume(String sessionId, TenantCtx ctx, String toolCallId, String toolName, String answer);
}
