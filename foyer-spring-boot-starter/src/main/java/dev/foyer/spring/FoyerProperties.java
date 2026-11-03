package dev.foyer.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "foyer")
public class FoyerProperties {

    /** HMAC secret for admission tokens, at least 32 characters. Share it with every service that verifies tokens. */
    private String secret;

    /** When the queue store is unreachable: {@code true} admits everyone (default), {@code false} answers 503. */
    private boolean failOpen = true;

    /**
     * URL prefix of the queue endpoints and the wait page: one or more {@code /segment} parts of
     * {@code [A-Za-z0-9_-]+}, no trailing slash. Used verbatim in routes, exclusion patterns and redirect URLs.
     */
    private String basePath = "/foyer";

    private static final Pattern BASE_PATH = Pattern.compile("(?:/[A-Za-z0-9_-]+)+");

    private final Cookie cookie = new Cookie();
    private final Poll poll = new Poll();
    private final Redis redis = new Redis();

    /** Rooms keyed by id ({@code [A-Za-z0-9_-]+}). */
    private final Map<String, Room> rooms = new LinkedHashMap<>();

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) {
        if (basePath == null || !BASE_PATH.matcher(basePath).matches()) {
            throw new IllegalArgumentException("foyer.base-path must be an absolute non-root path with segments "
                    + "containing only letters, digits, underscores and hyphens, and no trailing slash");
        }
        this.basePath = basePath;
    }
    public Cookie getCookie() { return cookie; }
    public Poll getPoll() { return poll; }
    public Redis getRedis() { return redis; }
    public Map<String, Room> getRooms() { return rooms; }

    public static class Room {
        /** Admissions per {@link #interval}. */
        private int rate = 10;
        private Duration interval = Duration.ofSeconds(1);
        /** Bucket capacity; how many may enter at once after an idle period. Defaults to {@link #rate}. */
        private Integer burst;
        /** Hard cap on concurrently admitted visitors; 0 = unlimited. */
        private int maxActive = 0;
        /** Lifetime of an admission token. */
        private Duration tokenTtl = Duration.ofMinutes(5);
        /** Waiters silent for longer than this lose their place. Must exceed {@code foyer.poll.max}. */
        private Duration queueTtl = Duration.ofSeconds(30);
        /** Ant-style path patterns in this application that require an admission token for this room. */
        private List<String> protect = new ArrayList<>();

        public int getRate() { return rate; }
        public void setRate(int rate) { this.rate = rate; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public Integer getBurst() { return burst; }
        public void setBurst(Integer burst) { this.burst = burst; }
        public int getMaxActive() { return maxActive; }
        public void setMaxActive(int maxActive) { this.maxActive = maxActive; }
        public Duration getTokenTtl() { return tokenTtl; }
        public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
        public Duration getQueueTtl() { return queueTtl; }
        public void setQueueTtl(Duration queueTtl) { this.queueTtl = queueTtl; }
        public List<String> getProtect() { return protect; }
        public void setProtect(List<String> protect) { this.protect = protect; }
    }

    public static class Cookie {
        /** Prefix of the per-room admission token cookie ({@code <tokenName>-<room>}). */
        private String tokenName = "foyer-token";
        /** Cookie carrying the anonymous visitor id used to hold a place in line. */
        private String visitorName = "foyer-visitor";
        private boolean secure = false;
        private String sameSite = "Lax";
        private String path = "/";
        private String domain;

        public String getTokenName() { return tokenName; }
        public void setTokenName(String tokenName) { this.tokenName = tokenName; }
        public String getVisitorName() { return visitorName; }
        public void setVisitorName(String visitorName) { this.visitorName = visitorName; }
        public boolean isSecure() { return secure; }
        public void setSecure(boolean secure) { this.secure = secure; }
        public String getSameSite() { return sameSite; }
        public void setSameSite(String sameSite) { this.sameSite = sameSite; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
    }

    public static class Poll {
        /** Shortest interval the server will ask clients to wait between polls. */
        private Duration min = Duration.ofSeconds(1);
        /** Longest interval the server will ask clients to wait between polls. */
        private Duration max = Duration.ofSeconds(10);

        public Duration getMin() { return min; }
        public void setMin(Duration min) { this.min = min; }
        public Duration getMax() { return max; }
        public void setMax(Duration max) { this.max = max; }
    }

    public static class Redis {
        /** Used only when the application defines no {@code StatefulRedisConnection<String,String>} bean. */
        private String uri = "redis://localhost:6379";
        /** Prefix of every key Foyer writes. */
        private String keyPrefix = "foyer";

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    }
}
