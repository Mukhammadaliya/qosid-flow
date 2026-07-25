package uz.greenwhite.gateway.ai;

import java.util.regex.Pattern;

/** Best-effort masking of obvious secrets before an error context is sent to an external LLM. */
public final class AiRedactor {

    private AiRedactor() {}

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern BASIC = Pattern.compile("(?i)(basic)\\s+[A-Za-z0-9+/=]+");
    // Matches both plain `key=value` / `key: value` and JSON `"key" : "value"` forms.
    // Negative lookahead keeps already-masked "Bearer ***" / "Basic ***" values intact.
    private static final Pattern KV = Pattern.compile(
            "(?i)(\"?)(authorization|password|passwd|token|api[_-]?key|secret)(\"?)\\s*([:=])\\s*(\"?)"
                    + "(?!bearer\\b|basic\\b|\\*{3})[^\"\\s,}]+");

    public static String redact(String text) {
        if (text == null) return "";
        String out = BEARER.matcher(text).replaceAll("$1 ***");
        out = BASIC.matcher(out).replaceAll("$1 ***");
        out = KV.matcher(out).replaceAll("$1$2$3$4$5***");
        return out;
    }
}
