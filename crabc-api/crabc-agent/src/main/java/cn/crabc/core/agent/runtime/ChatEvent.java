package cn.crabc.core.agent.runtime;

/**
 * Agent 过程事件（防腐层自己的事件模型，映射到 SSE 八事件 + chart/slots，见 chatView/docs/05 §8）
 *
 * @author chatview
 */
public class ChatEvent {

    public static final String TEXT = "text";
    public static final String TOOL_CALL = "tool_call";
    public static final String THINKING = "thinking";
    public static final String ERROR = "error";
    public static final String DONE = "done";

    private final String type;
    private final Object payload;

    public ChatEvent(String type, Object payload) {
        this.type = type;
        this.payload = payload;
    }

    public static ChatEvent text(String delta) {
        return new ChatEvent(TEXT, delta);
    }

    public static ChatEvent tool(String toolName) {
        return new ChatEvent(TOOL_CALL, toolName);
    }

    public static ChatEvent error(String message) {
        return new ChatEvent(ERROR, message);
    }

    public static ChatEvent done(Object result) {
        return new ChatEvent(DONE, result);
    }

    public String getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }
}
