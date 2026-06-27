package network.vonix.guardian.core.theme;

import java.util.Objects;

/**
 * Immutable colour palette used for chat output.
 *
 * <p>Colour values are stored as upper-case 7-character hex strings of the form
 * {@code "#RRGGBB"}. Loader-specific bridges translate these into the appropriate
 * Minecraft text-colour representation (e.g. {@code TextColor.fromHexString} on
 * 1.21.1, or the nearest {@code ChatFormatting} on 1.18.2).</p>
 *
 * <p>Themes are pure value objects: they carry no behaviour and are safe to share
 * across threads.</p>
 *
 * @param name      lower-case identifier (e.g. {@code "aqua"})
 * @param primary   dominant accent colour — command output highlights
 * @param secondary supporting accent — labels, secondary text
 * @param success   success / positive confirmation
 * @param error     error / destructive operation feedback
 * @param warning   warning / caution
 * @param muted     muted / de-emphasised metadata text
 */
public record Theme(
    String name,
    String primary,
    String secondary,
    String success,
    String error,
    String warning,
    String muted
) {
    /**
     * Canonical constructor — validates that all fields are non-null and non-blank.
     *
     * @throws NullPointerException     if any field is null
     * @throws IllegalArgumentException if any field is blank
     */
    public Theme {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(secondary, "secondary");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(warning, "warning");
        Objects.requireNonNull(muted, "muted");
        if (name.isBlank()
            || primary.isBlank()
            || secondary.isBlank()
            || success.isBlank()
            || error.isBlank()
            || warning.isBlank()
            || muted.isBlank()) {
            throw new IllegalArgumentException("Theme fields must be non-blank");
        }
    }
}
