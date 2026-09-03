package uz.greenwhite.gateway.util;

/**
 * Renders a Throwable as one bounded log line.
 *
 * <p>Two log habits filled a demo box with 4.7 GB in three days. Logging
 * {@code e.getMessage()} prints the literal word "null" for exceptions that carry no message
 * ({@code TimeoutException} is the common one), which turns a repeating fault into thousands
 * of lines that name neither the fault nor its type. And logging an upstream error body
 * verbatim writes the whole thing — a 404 page from a Tomcat/Cloudflare origin is about 2 KB
 * of HTML, once in the caught message and again in the wrapped rethrow.
 *
 * <p>Use this for the recurring, expected-in-outage failures (a broker that is down, an
 * upstream that 404s on every poll). Full detail belongs at DEBUG, where an operator can turn
 * it on for the minute they need it instead of paying for it around the clock.
 */
public final class ErrorText {

    /** Roughly one terminal line. Long enough to keep an upstream's first, useful sentence. */
    private static final int MAX_MESSAGE = 300;

    private ErrorText() {}

    public static String brief(Throwable t) {
        if (t == null) return "unknown error";
        String type = t.getClass().getSimpleName();
        String message = t.getMessage();
        if (message == null || message.isBlank()) return type;
        String oneLine = message.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > MAX_MESSAGE) {
            oneLine = oneLine.substring(0, MAX_MESSAGE) + "…";
        }
        return type + ": " + oneLine;
    }
}
