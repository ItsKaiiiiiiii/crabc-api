package cn.crabc.core.app.filter;

import cn.crabc.core.app.entity.BaseApiLog;
import cn.crabc.core.app.entity.BaseApp;
import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import cn.crabc.core.app.service.system.IBaseApiLogService;
import cn.crabc.core.app.util.ApiThreadLocal;
import cn.crabc.core.app.util.RequestUtils;
import cn.crabc.core.app.util.SM3Util;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * API开放接口鉴权过滤 拦截器
 *
 * @author yuqf
 */

public class AuthInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    // API开放接口前缀
    private static final String API_PRE = "/api/web/";
    @Autowired
    private IBaseApiLogService iBaseApiLogService;
    @Value("${crabc.auth.expiresTime:10}")
    private Integer expiresTime;

    @Autowired
    @Qualifier("apiCache")
    private Cache<String, Object> apiCache;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        Object apiData = apiCache.getIfPresent(method + "_" + path.replace(API_PRE, ""));
        if (apiData == null) {
            throw new CustomException(ErrorStatusEnum.API_INVALID.getCode(), ErrorStatusEnum.API_INVALID.getMassage());
        }
        ApiInfoDTO apiInfo = (ApiInfoDTO) apiData;
        if (apiInfo.getEnabled() == 0) {
            throw new CustomException(ErrorStatusEnum.API_OFFLINE.getCode(), ErrorStatusEnum.API_OFFLINE.getMassage());
        }

        // 应用列表
        List<BaseApp> appList = apiInfo.getAppList();

        boolean auth = switch (apiInfo.getAuthType().toUpperCase()) {
            case "APP_CODE" -> checkAppCode(request, appList);
            case "APP_KEY" -> checkAppKey(request, appList);
            case "APP_SECRET" -> checkSM3(request, appList);
            default -> true;
        };

        if (!auth) {
            throw new CustomException(ErrorStatusEnum.API_UN_AUTH.getCode(), ErrorStatusEnum.API_UN_AUTH.getMassage());
        }

        // 存入当前时间，当作是日志的请求时间
        apiInfo.setRequestDate(new Date());
        apiInfo.setRequestTime(System.currentTimeMillis());
        // 放入上下文
        ApiThreadLocal.set(apiInfo);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        addLog(request, response, ex);
        // 清除上下文
        ApiThreadLocal.remove();
    }

    /**
     * 记录访问日志
     *
     * @param request
     * @param response
     */
    private void addLog(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        BaseApiLog apiLog = new BaseApiLog();
        long endTime = System.currentTimeMillis();
        ApiInfoDTO apiInfo = ApiThreadLocal.get();
        if (apiInfo == null) {
            return;
        }
        apiLog.setApiId(apiInfo.getApiId());
        apiLog.setApiName(apiInfo.getApiName());
        apiLog.setApiPath(request.getRequestURI());
        apiLog.setApiMethod(apiInfo.getApiMethod());
        apiLog.setAuthType(apiInfo.getAuthType());
        apiLog.setRequestIp(RequestUtils.getIp(request));
        apiLog.setRequestTime(apiInfo.getRequestDate());
        apiLog.setResponseTime(new Date());
        apiLog.setCostTime(endTime - apiInfo.getRequestTime());
        apiLog.setQueryParam(request.getQueryString());
        apiLog.setResponseCode(response.getStatus());
        apiLog.setRequestStatus(response.getStatus() == 200 ? "success" : "fail");
        try {
            if (request instanceof BaseRequestWrapper) {
                String requestBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
                apiLog.setRequestBody(requestBody);
            }
        } catch (Exception e) {
            log.error("响应结果转换异常", e);
        }
        iBaseApiLogService.addLog(apiLog);
    }

    /**
     * 验证接口访问权限APP_CODE
     *
     * @param request
     * @param appList
     * @return
     */
    private boolean checkAppCode(HttpServletRequest request, List<BaseApp> appList) {
        String appCode = RequestUtils.getAppCode(request);
        if (appCode == null || appCode.isEmpty()) {
            return false;
        }
        return appList.stream().anyMatch(app -> app.getAppCode().equals(appCode));
    }
    /**
     * 验证接口访问权限APP_KEY
     *
     * @param request
     * @param appList
     * @return
     */
    private boolean checkAppKey(HttpServletRequest request, List<BaseApp> appList) {
        String appKey = RequestUtils.getAppKey(request);
        if (appKey == null || appKey.isEmpty()) {
            return false;
        }
        return appList.stream().anyMatch(app -> app.getAppKey().equals(appKey));
    }

    /**
     * 国密签名认证
     *
     * @param request
     * @param appList
     * @return
     * @throws Exception
     */
    public boolean checkSM3(HttpServletRequest request, List<BaseApp> appList) {
        // 认证参数
        String sign = Optional.ofNullable(request.getHeader("sign")).orElse(request.getParameter("sign"));
        String timeStamp = Optional.ofNullable(request.getHeader("timestamp")).orElse(request.getParameter("timestamp"));
        String appKey = Optional.ofNullable(request.getHeader("appkey")).orElse(request.getParameter("appkey"));
        if (appKey == null || sign == null || timeStamp == null) {
            throw new CustomException(ErrorStatusEnum.SHA_PARAM_NOT_FOUNT.getCode(), ErrorStatusEnum.PARAM_NOT_FOUNT.getMassage());
        }
        // 校验时间戳,超过10分钟失效
        long authTime = Long.parseLong(timeStamp);
        long nowTime = System.currentTimeMillis() - authTime;
        if (nowTime > expiresTime * 60 * 1000) {
            throw new CustomException(ErrorStatusEnum.SHA_TIMESTAMP_EXPIRE.getCode(), ErrorStatusEnum.SHA_TIMESTAMP_EXPIRE.getMassage());
        }

        String appSecret = appList.stream()
                .filter(app -> app.getAppKey().equals(appKey))
                .map(BaseApp::getAppSecret)
                .findFirst()
                .orElse("");

        return SM3Util.verify(appSecret + timeStamp, sign);
    }
}
