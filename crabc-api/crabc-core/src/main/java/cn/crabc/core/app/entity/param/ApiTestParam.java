package cn.crabc.core.app.entity.param;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Setter
@Getter
public class ApiTestParam {

    private String sqlScript;

    private String datasourceId;

    private String datasourceType;

    private String schemaName;

    private String sqlParams;

    private Integer pageSetup;

    private String resultType;

    private Object requestParams;

    private String bodyData;

    private Map<String,Object> queryParam;
}
