package uz.greenwhite.gateway.logs;

/** Builds the LogQL selector for each of the three error sources. */
public final class LogQlBuilder {

    private LogQlBuilder() {}

    public static String build(String source, String q, String container, String level) {
        String base = switch (source == null ? "" : source) {
            case "dlq" -> "{stream=\"dlq\"}";
            case "jar" -> (level == null || level.isBlank())
                    ? "{job=\"qosid-jar\"}"
                    : "{job=\"qosid-jar\",level=\"" + level + "\"}";
            case "docker" -> {
                String sel = (container == null || container.isBlank())
                        ? "{container=~\".+\"}"
                        : "{container=~\"" + container + "\"}";
                yield (level == null || level.isBlank())
                        ? sel
                        : sel + " |~ \"(?i)" + level.toLowerCase() + "\"";
            }
            default -> throw new IllegalArgumentException("unknown source: " + source);
        };
        if (q != null && !q.isBlank()) {
            base = base + " |= \"" + q + "\"";
        }
        return base;
    }
}
