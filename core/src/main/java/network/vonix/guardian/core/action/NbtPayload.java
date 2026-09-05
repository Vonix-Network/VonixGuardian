package network.vonix.guardian.core.action;

/**
 * Bounded, deterministic size policy for opaque NBT / component payloads stored
 * in the existing v5/v8 blob columns.
 *
 * <p>{@link #admit(byte[])} drops oversized payloads rather than truncating them.
 * That helper is a persistence-size filter only; it is not a fidelity-preserving
 * normalization boundary for {@code Action} construction. A non-null payload
 * larger than {@link #MAX_BYTES} must remain distinguishable from genuine
 * absence until an explicit admission or decode rejection.
 */
public final class NbtPayload {

    /** Hard cap matching loader {@code NbtCapture} (512 KiB). */
    public static final int MAX_BYTES = 512 * 1024;

    private NbtPayload() {}

    /**
     * Admit a payload for persistence. Empty arrays are kept; {@code null} is
     * unchanged; payloads larger than {@link #MAX_BYTES} are dropped.
     *
     * @param bytes raw NBT bytes, or {@code null}
     * @return {@code bytes} if admissible, otherwise {@code null}
     */
    public static byte[] admit(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length > MAX_BYTES) {
            return null;
        }
        return bytes;
    }

    /** @return {@code true} when {@code bytes} exceeds {@link #MAX_BYTES} */
    public static boolean tooLarge(byte[] bytes) {
        return bytes != null && bytes.length > MAX_BYTES;
    }
}
