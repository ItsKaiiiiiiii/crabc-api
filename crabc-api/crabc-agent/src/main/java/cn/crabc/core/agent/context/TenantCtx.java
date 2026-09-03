package cn.crabc.core.agent.context;

/**
 * 租户上下文（chatView docs/05 §4.3）
 *
 * 经 AgentScope RuntimeContext 以 POJO 形式透传到每一次工具调用，
 * 工具方法签名里放一个 TenantCtx 参数即可自动注入。
 * 所有语义层查询、数据源解析、SQL 白名单只认本对象——权限剔除只在
 * SemanticQueryService 一处实现（一处实现才可测）。
 *
 * @author chatview
 */
public class TenantCtx {

    private String tenantId;
    private Long userId;
    private String sessionId;
    private String datasourceId;

    public TenantCtx() {
    }

    public TenantCtx(String tenantId, Long userId, String sessionId, String datasourceId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.datasourceId = datasourceId;
    }

    public String getTenantId() {
        return tenantId == null ? "default" : tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(String datasourceId) {
        this.datasourceId = datasourceId;
    }
}
