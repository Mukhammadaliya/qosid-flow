package uz.greenwhite.qosid.observability.logs;

/** Builds the LogQL selector for each of the three error sources. */
public final class LogQlBuilder {

    private LogQlBuilder() {}

    public static String build(String source, String q, String container, String level) {
        String base = switch (source == null ? "" : source) {
            case "dlq" -> "{stream=\"dlq\"}";
            case "jar" -> (level == null || level.isBlank())
                    ? "{job=\"qosid-jar\"}"
                    : "{job=\"qosid-jar\",level=\"" + escape(level) + "\"}";
            case "docker" -> {
                String sel = (container == null || container.isBlank())
                        ? "{container=~\".+\"}"
                        : "{container=~\"" + escape(container) + "\"}";
                yield (level == null || level.isBlank())
                        ? sel
                        : sel + " |~ \"(?i)" + escape(level.toLowerCase()) + "\"";
            }
            default -> throw new IllegalArgumentException("unknown source: " + source);
        };
        if (q != null && !q.isBlank()) {
            base = base + " |= \"" + escape(q) + "\"";
        }
        return base;
    }

    /** Escapes backslashes and double quotes so user input cannot break out of a LogQL string. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
