package network.vonix.guardian.core.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Salted SHA-256 hasher for IP / hostname values persisted to SESSION_JOIN rows.
 *
 * <p>Activated when {@link GuardianConfig.Privacy#hashIps()} is {@code true}. Produces a stable,
 * salt-dependent 64-bit prefix ({@code sha256:<16hex>}) — enough to correlate sessions belonging
 * to the same connecting peer without storing the raw address.
 *
 * @since 0.1.0
 */
public final class IpHasher {

    private IpHasher() {
        // utility
    }

    /**
     * Compute the salted hash of {@code ipOrHostname}.
     *
     * @param ipOrHostname the value to hash; {@code null} or empty short-circuits to {@code ""}
     * @param salt         non-null salt (length validation is the caller's job)
     * @return {@code sha256:<16hex>} or {@code ""} for null / empty input
     */
    public static String hash(String ipOrHostname, String salt) {
        if (ipOrHostname == null || ipOrHostname.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest(ipOrHostname.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 16); // 64-bit prefix
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
