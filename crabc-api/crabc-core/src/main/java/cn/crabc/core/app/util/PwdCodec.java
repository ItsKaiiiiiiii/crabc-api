package cn.crabc.core.app.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码编解码（chatView 安全改造：MD5 无盐 → PBKDF2WithHmacSHA256）
 *
 * 存储格式：pbkdf2:&lt;iterations&gt;:&lt;saltB64&gt;:&lt;hashB64&gt;
 * 兼容策略：存量 admin 的 MD5 口令在首次登录校验通过后自动升级为 PBKDF2（透明迁移）。
 *
 * @author chatview
 */
public final class PwdCodec {

    private static final String PREFIX = "pbkdf2:";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /** 迭代次数：OWASP 2023 建议 SHA-256 至少 60 万次 */
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_LENGTH = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PwdCodec() {
    }

    /** 加密：返回 pbkdf2:iterations:saltB64:hashB64 */
    public static String encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, ITERATIONS);
        return PREFIX + ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt)
                + ":" + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 校验：自动识别 PBKDF2 与历史 MD5 两种存储格式
     *
     * @return 0=匹配；1=不匹配；2=匹配但为历史 MD5（调用方应升级存储）
     */
    public static int verify(String stored, String rawPassword) {
        if (stored == null || rawPassword == null) {
            return 1;
        }
        if (stored.startsWith(PREFIX)) {
            String[] parts = stored.substring(PREFIX.length()).split(":");
            if (parts.length != 3) {
                return 1;
            }
            try {
                int iterations = Integer.parseInt(parts[0]);
                byte[] salt = Base64.getDecoder().decode(parts[1]);
                byte[] expect = Base64.getDecoder().decode(parts[2]);
                byte[] actual = pbkdf2(rawPassword, salt, iterations);
                return MessageDigest.isEqual(expect, actual) ? 0 : 1;
            } catch (Exception e) {
                return 1;
            }
        }
        // 历史 MD5（大写）比对
        boolean legacyMatch = Md5Utils.hash(rawPassword).toUpperCase().equals(stored);
        return legacyMatch ? 2 : 1;
    }

    /**
     * 是否历史 MD5 口令（登录后需升级）
     */
    public static boolean isLegacyMd5(String stored) {
        return stored != null && !stored.startsWith(PREFIX);
    }

    private static byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_LENGTH);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码编码失败", e);
        }
    }
}
