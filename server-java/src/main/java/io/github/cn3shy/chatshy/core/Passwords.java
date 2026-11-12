package io.github.cn3shy.chatshy.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.bouncycastle.crypto.generators.SCrypt;

/**
 * 密码与 Key 工具。scrypt 参数与哈希格式与 Node 版完全一致：
 * scrypt$N$r$p$saltHex$hashHex（N=16384, r=8, p=1, 64 字节）。
 * 现有 SQLite 库里的管理员密码哈希可以直接验证，无需重置。
 */
public final class Passwords {
    private static final int SCRYPT_N = 16_384;
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;
    private static final int KEY_LEN = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LEN);
        return "scrypt$" + SCRYPT_N + "$" + SCRYPT_R + "$" + SCRYPT_P + "$"
                + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(hash);
    }

    public static boolean verifyPassword(String password, String stored) {
        if (stored == null) return false;
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 6 || !parts[0].equals("scrypt")) return false;
        try {
            int n = Integer.parseInt(parts[1]);
            int r = Integer.parseInt(parts[2]);
            int p = Integer.parseInt(parts[3]);
            byte[] salt = HexFormat.of().parseHex(parts[4]);
            byte[] expected = HexFormat.of().parseHex(parts[5]);
            if (expected.length == 0) return false;
            byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, n, r, p, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String randomBase64Url(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String newAdminPassword() {
        return randomBase64Url(12);
    }

    public static String newCallerKey() {
        return "sk-chatshy-" + randomBase64Url(32);
    }

    /** 先各自 SHA-256 再比较，长度恒等；不会因提前返回泄漏 Key 长度。 */
    public static boolean constantTimeEquals(String expected, String provided) {
        byte[] a = sha256(expected);
        byte[] b = sha256(provided);
        return MessageDigest.isEqual(a, b);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
