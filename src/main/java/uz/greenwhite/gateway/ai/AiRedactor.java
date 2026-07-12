package uz.greenwhite.gateway.ai;

import java.util.regex.Pattern;

/** Best-effort masking of obvious secrets before an error context is sent to an external LLM. */
public final class AiRedactor {

    private AiRedactor() {}

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[A-Za-z0-9._\\-]+");
    // Negative lookahead keeps an already-masked "Authorization: Bearer ***" intact.
    private static final Pattern KV = Pattern.compile(
            "(?i)(authorization|password|passwd|token|api[_-]?key|secret)\\s*([:=])\\s*(?!bearer\\b)(\\S+)");

    public static String redact(String text) {
        if (text == null) return "";
        String out = BEARER.matcher(text).replaceAll("$1 ***");
        out = KV.matcher(out).replaceAll("$1$2***");
        return out;
    }
}
