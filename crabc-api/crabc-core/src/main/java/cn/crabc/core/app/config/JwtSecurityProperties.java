package cn.crabc.core.app.config;

import cn.crabc.core.app.util.JwtUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 安全配置（chatView 安全改造 #1：secret 配置化 + 启动强校验）
 *
 * 修复隐患：原 JwtUtil 的 HS256 secret 硬编码在源码，所有部署实例同密钥。
 * 现在从配置注入（环境变量优先），缺失或强度不足直接拒绝启动。
 *
 * 配置示例（生产必须用环境变量覆盖）：
 *   crabc:
 *     jwt:
 *       secret: ${CRABC_JWT_SECRET}          # ≥32 字节
 *       expires-hours: 8                     # access token 有效期
 *       refresh-expires-hours: 720           # refresh token 有效期（30 天）
 *
 * @author chatview
 */
@Component
public class JwtSecurityProperties {

    private static final Logger log = LoggerFactory.getLogger(JwtSecurityProperties.class);

    @Value("${crabc.jwt.secret:}")
    private String secret;

    @Value("${crabc.jwt.expires-hours:8}")
    private long expiresHours;

    @Value("${crabc.jwt.refresh-expires-hours:720}")
    private long refreshExpiresHours;

    @PostConstruct
    public void init() {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "[chatview 安全检查] 缺少 crabc.jwt.secret 配置（建议环境变量 CRABC_JWT_SECRET），拒绝启动。"
                            + "生成示例：openssl rand -base64 48");
        }
        if (secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "[chatview 安全检查] crabc.jwt.secret 强度不足（HS256 要求 ≥32 字节），拒绝启动");
        }
        JwtUtil.configure(secret, expiresHours * 3600_000L, refreshExpiresHours * 3600_000L);
        log.info("[chatview] JWT secret 已从配置加载，access {}h / refresh {}h", expiresHours, refreshExpiresHours / 24);
    }
}
