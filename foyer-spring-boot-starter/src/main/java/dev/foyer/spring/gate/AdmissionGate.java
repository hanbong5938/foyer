package dev.foyer.spring.gate;

import dev.foyer.core.AdmissionTokenCodec;
import dev.foyer.spring.FoyerProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Stateless check used by the MVC interceptor, the WebFlux filter and the Gateway filter: does this request
 * carry a valid admission token for the room? Never touches the queue store.
 */
public final class AdmissionGate {

    public static final String TOKEN_HEADER = "X-Foyer-Token";

    public enum Verdict {
        /** Valid token: let the request through. */
        ALLOW,
        /** Browser navigation without a token: send it to the wait page. */
        REDIRECT,
        /** API call without a token: answer 429 with the wait page URL in the body. */
        REJECT
    }

    public record Decision(Verdict verdict, String waitUrl) {
        public boolean allowed() {
            return verdict == Verdict.ALLOW;
        }
    }

    private final AdmissionTokenCodec codec;
    private final String basePath;
    private final String tokenCookiePrefix;

    public AdmissionGate(AdmissionTokenCodec codec, FoyerProperties properties) {
        this.codec = codec;
        this.basePath = properties.getBasePath();
        this.tokenCookiePrefix = properties.getCookie().getTokenName();
    }

    public String tokenCookieName(String room) {
        return tokenCookiePrefix + "-" + room;
    }

    public boolean isValid(String room, String token) {
        return token != null && codec.decode(token).filter(t -> room.equals(t.room())).isPresent();
    }

    /**
     * @param token        cookie or header value, may be {@code null}
     * @param wantsHtml    whether the client is a browser navigation (Accept: text/html)
     * @param contextPath  application mount point ({@code ""} at the host root, otherwise e.g. {@code /app})
     * @param originalPath path (with query) to return to after admission, including the application prefix
     */
    public Decision check(String room, String token, boolean wantsHtml, String contextPath, String originalPath) {
        if (isValid(room, token)) return new Decision(Verdict.ALLOW, null);
        String waitUrl = waitUrl(contextPath, room, originalPath);
        return new Decision(wantsHtml ? Verdict.REDIRECT : Verdict.REJECT, waitUrl);
    }

    public String waitUrl(String contextPath, String room, String redirect) {
        String url = contextPath + basePath + "/" + room + "/wait";
        if (redirect == null || redirect.isEmpty()) return url;
        return url + "?redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8);
    }

    public static boolean wantsHtml(String accept, String secFetchMode) {
        if (secFetchMode != null) return "navigate".equalsIgnoreCase(secFetchMode);
        return accept != null && accept.contains("text/html");
    }

    /**
     * Only same-origin paths are accepted as post-admission redirect targets (no open redirect); anything else
     * falls back to the application root under {@code contextPath}. Control characters are rejected outright:
     * browsers strip tab/CR/LF while parsing URLs, so {@code "/\t/evil"} would become {@code "//evil"}.
     */
    public static String safeRedirect(String contextPath, String redirect) {
        if (redirect == null || redirect.isEmpty()) return contextPath + "/";
        if (!redirect.startsWith("/") || redirect.startsWith("//") || redirect.startsWith("/\\")) return contextPath + "/";
        for (int i = 0; i < redirect.length(); i++) {
            char c = redirect.charAt(i);
            if (c < 0x20 || c == 0x7f) return contextPath + "/";
        }
        return redirect;
    }

    /** Body of a 429 answer on a protected path. */
    public static String rejectBody(String room, String waitUrl) {
        return "{\"error\":\"waiting_room\",\"room\":" + jsonString(room) + ",\"waitUrl\":" + jsonString(waitUrl) + "}";
    }

    /** JSON string literal that is also safe inside an HTML {@code <script>} element. */
    public static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
