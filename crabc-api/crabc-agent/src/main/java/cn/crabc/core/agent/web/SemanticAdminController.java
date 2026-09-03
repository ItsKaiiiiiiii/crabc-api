package cn.crabc.core.agent.web;

import cn.crabc.core.agent.semantic.SchemaSyncService;
import cn.crabc.core.app.util.UserThreadLocal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语义层管理接口（M1：手动触发同步；M2 接入管理端租户化界面）
 *
 * @author chatview
 */
@RestController
@RequestMapping("/api/v1/semantic")
public class SemanticAdminController {

    @Autowired
    private SchemaSyncService schemaSyncService;

    /** 触发一个数据源的结构 + 值域同步：{datasourceId, schema} */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody Map<String, Object> body) {
        String tenantId = UserThreadLocal.getTenantId();
        String datasourceId = String.valueOf(body.get("datasourceId"));
        String schema = body.get("schema") == null ? null : String.valueOf(body.get("schema"));
        int tables = schemaSyncService.syncDatasource(tenantId, datasourceId, schema);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", tables);
        result.put("message", "同步完成");
        return result;
    }
}
