package dev.hoori.agents.claude.internal.session;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Removes invisible/format Unicode characters from user-supplied tags for CLI filter
 * compatibility (Python: {@code _sanitize_unicode}).
 */
public final class UnicodeSanitizer {

    /** Explicit ranges (redundant with the category check, kept for parity with the TS CLI). */
    private static final Pattern STRIP = Pattern.compile(
            "[\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff\\ue000-\\uf8ff]");

    private static final int MAX_ITERATIONS = 10;

    private UnicodeSanitizer() {
    }

    /**
     * Iteratively applies NFKC and strips format (Cf), private-use (Co) and unassigned (Cn)
     * characters until the value is stable (at most 10 rounds).
     */
    public static String sanitize(String value) {
        String current = value;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            String previous = current;
            current = Normalizer.normalize(current, Normalizer.Form.NFKC);
            StringBuilder kept = new StringBuilder(current.length());
            current.codePoints().filter(cp -> !isStripped(cp)).forEach(kept::appendCodePoint);
            current = STRIP.matcher(kept).replaceAll("");
            if (current.equals(previous)) {
                break;
            }
        }
        return current;
    }

    private static boolean isStripped(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.UNASSIGNED;
    }
}
