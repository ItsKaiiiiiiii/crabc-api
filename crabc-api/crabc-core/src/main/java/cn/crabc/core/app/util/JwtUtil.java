package cn.crabc.core.app.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Jwt工具类
 *
 * @author yuqf
 */
public class JwtUtil {
    private static String header = "Authorization";
    // 令牌秘钥：chatView 安全改造 #1 —— 不再硬编码，由 JwtSecurityProperties 从配置注入（启动强校验）
    private static String secret = null;
    // 默认 8 小时，可由 crabc.jwt.expires-hours 覆盖
    public static long expirationTime = 1000L * 60 * 60 * 8;
    // refresh token 有效期，默认 30 天，可由 crabc.jwt.refresh-expires-hours 覆盖
    private static long refreshExpirationTime = 1000L * 60 * 60 * 24 * 30;
    public static final String TOKEN_PREFIX = "bearer ";
    public static final String CLAIM_TENANT = "tenantId";
    public static final String CLAIM_TYPE = "tokenType";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    /**
     * chatView：由 JwtSecurityProperties 在启动时调用（secret 已强校验）
     */
    public static void configure(String configuredSecret, long accessExpiresMillis, long refreshExpiresMillis) {
        secret = configuredSecret;
        expirationTime = accessExpiresMillis;
        refreshExpirationTime = refreshExpiresMillis;
    }

    /**
     * 创建令牌（chatView：默认租户）
     */
    public static String createToken(Long userId, String userName) {
        return createToken(userId, userName, "default");
    }

    /**
     * 创建令牌（chatView：携带租户三元组中的 tenantId）
     */
    public static String createToken(Long userId, String userName, String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("userName", userName);
        claims.put(CLAIM_TENANT, tenantId == null ? "default" : tenantId);
        claims.put(CLAIM_TYPE, TYPE_ACCESS);
        return createToken(claims);
    }

    /**
     * chatView：refresh token（真实现，替代原随机 UUID——无刷新逻辑的问题）
     */
    public static String createRefreshToken(Long userId, String userName, String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("userName", userName);
        claims.put(CLAIM_TENANT, tenantId == null ? "default" : tenantId);
        claims.put(CLAIM_TYPE, TYPE_REFRESH);
        String uuid = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiration = new Date(now.getTime() + refreshExpirationTime);
        try {
            return Jwts.builder()
                    .header()
                    .add("typ", "JWT")
                    .add("alg", "HS256")
                    .and()
                    .claims(claims)
                    .expiration(expiration)
                    .id(uuid)
                    .signWith(Keys.hmacShaKeyFor(secret.getBytes()))
                    .compact();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * chatView：解析 token 类型（access/refresh）
     */
    public static String getTokenType(Claims claims) {
        Object type = claims == null ? null : claims.get(CLAIM_TYPE);
        return type == null ? TYPE_ACCESS : type.toString();
    }

    /**
     * chatView：从 claims 取租户
     */
    public static String getTenantId(Claims claims) {
        Object tenant = claims == null ? null : claims.get(CLAIM_TENANT);
        return tenant == null ? "default" : tenant.toString();
    }

    /**
     * 生成令牌
     */
    public static String createToken(Map<String, Object> claims) {
        String uuid = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationTime);
        try {
            return Jwts.builder()
                    .header()
                    .add("typ", "JWT")
                    .add("alg", "HS256")
                    .and()
                    .claims(claims)
                    .expiration(expiration)
                    .id(uuid)
                    .signWith(Keys.hmacShaKeyFor(secret.getBytes()))
                    .compact();
        }catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 从令牌中获取数据声明
     *
     * @param token 令牌
     * @return 数据声明
     */
    public static Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secret.getBytes()))
                    .build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 从令牌中获取用户名
     *
     * @param token 令牌
     * @return 用户名
     */
    public static String getUserId(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    /**
     * 获取请求token
     *
     * @param request
     * @return token
     */
    public static String getToken(HttpServletRequest request) {
        String token = request.getHeader(header);
        if (token != null && token.startsWith(TOKEN_PREFIX)) {
            token = token.replace(TOKEN_PREFIX, "");
        }
        return token;
    }
}
