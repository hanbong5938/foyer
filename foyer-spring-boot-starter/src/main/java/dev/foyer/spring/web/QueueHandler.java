package dev.foyer.spring.web;

import dev.foyer.core.WaitingRoomUnavailableException;
import dev.foyer.spring.FoyerProperties;
import dev.foyer.spring.FoyerService;
import dev.foyer.spring.UnknownRoomException;
import dev.foyer.spring.gate.AdmissionGate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;

/**
 * Framework-neutral queue endpoints. The MVC and WebFlux routers translate {@link Reply} into their response type.
 *
 * <pre>
 * POST   {base}/{room}/enter    join the line (or re-check); sets the visitor cookie
 * GET    {base}/{room}/status   poll; sets the admission cookie once admitted
 * DELETE {base}/{room}          leave the line
 * GET    {base}/{room}/wait     bundled wait page, ?redirect=/path to return to
 * </pre>
 */
public final class QueueHandler {

    public record Reply(int status, Object body, List<ResponseCookie> cookies, Map<String, String> headers) {
        public boolean isHtml() {
            return body instanceof String;
        }
    }

    private static final Duration VISITOR_COOKIE_TTL = Duration.ofDays(1);
    private static final Pattern VISITOR_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final FoyerService service;
    private final FoyerProperties properties;
    private final AdmissionGate gate;
    private final WaitPage waitPage;
    private final Clock clock;

    public QueueHandler(FoyerService service, FoyerProperties properties, AdmissionGate gate, WaitPage waitPage, Clock clock) {
        this.service = service;
        this.properties = properties;
        this.gate = gate;
        this.waitPage = waitPage;
        this.clock = clock;
    }

    public String visitorCookieName() {
        return properties.getCookie().getVisitorName();
    }

    public CompletionStage<Reply> enter(String room, String visitorId, String contentType) {
        if (!isJson(contentType)) {
            return java.util.concurrent.CompletableFuture.completedStage(new Reply(
                    415, Map.of("error", "unsupported_media_type", "room", room),
                    List.of(), Map.of("Cache-Control", "no-store")));
        }
        List<ResponseCookie> cookies = new ArrayList<>(2);
        boolean fresh = !isVisitorId(visitorId);
        String id = fresh ? UUID.randomUUID().toString() : visitorId;
        if (fresh) cookies.add(cookie(visitorCookieName(), id, VISITOR_COOKIE_TTL));
        return guard(room, () -> service.enter(room, id).thenApply(outcome -> reply(room, outcome, cookies)));
    }

    public CompletionStage<Reply> status(String room, String visitorId) {
        if (!isVisitorId(visitorId)) {
            return guard(room, () -> {
                service.rooms().require(room);
                return java.util.concurrent.CompletableFuture.completedStage(
                        new Reply(200, StatusResponse.notInQueue(), List.of(), Map.of("Cache-Control", "no-store")));
            });
        }
        return guard(room, () -> service.poll(room, visitorId).thenApply(outcome -> reply(room, outcome, new ArrayList<>(1))));
    }

    public CompletionStage<Reply> leave(String room, String visitorId) {
        if (!isVisitorId(visitorId)) {
            return guard(room, () -> {
                service.rooms().require(room);
                return java.util.concurrent.CompletableFuture.completedStage(new Reply(204, null, List.of(), Map.of()));
            });
        }
        return guard(room, () -> service.leave(room, visitorId)
                .thenApply(v -> new Reply(204, null, List.of(), Map.of())));
    }

    /** @param contextPath application mount point ({@code ""} at the host root), prepended to the page's endpoint base */
    public Reply waitPage(String contextPath, String room, String redirect) {
        if (service.rooms().find(room).isEmpty()) return unknownRoom(room);
        return new Reply(200, waitPage.render(contextPath, room, redirect), List.of(), Map.of("Cache-Control", "no-store"));
    }

    static boolean isJson(String contentType) {
        if (contentType == null) return false;
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException invalid) {
            return false;
        }
        return "application".equalsIgnoreCase(mediaType.getType())
                && "json".equalsIgnoreCase(mediaType.getSubtype());
    }

    /**
     * Visitor cookies are issued by {@link #enter} as canonical lower-case UUIDs; anything else (absent, oversized,
     * malformed) is treated as no cookie at all so arbitrary client input never becomes a store identifier.
     */
    private static boolean isVisitorId(String visitorId) {
        return visitorId != null && visitorId.length() == 36 && VISITOR_ID.matcher(visitorId).matches();
    }

    private Reply reply(String room, FoyerService.Outcome outcome, List<ResponseCookie> cookies) {
        if (outcome.token() != null) {
            Duration ttl = Duration.between(clock.instant(), outcome.status().admittedUntil());
            cookies.add(cookie(gate.tokenCookieName(room), outcome.token(), cookieLifetime(ttl)));
        }
        return new Reply(200, StatusResponse.of(outcome), cookies, Map.of("Cache-Control", "no-store"));
    }

    /**
     * {@code Max-Age} is whole seconds, so a positive remainder is rounded up rather than truncated to zero (which
     * would delete the cookie of a live admission). The cookie outliving the token grants nothing: the gate checks
     * the token's own expiry. Expired admissions still clear the cookie.
     */
    static Duration cookieLifetime(Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) return Duration.ZERO;
        return Duration.ofSeconds(ttl.getNano() == 0 ? ttl.getSeconds() : ttl.getSeconds() + 1);
    }

    private CompletionStage<Reply> guard(String room, java.util.function.Supplier<CompletionStage<Reply>> op) {
        CompletionStage<Reply> stage;
        try {
            stage = op.get();
        } catch (RuntimeException e) {
            return java.util.concurrent.CompletableFuture.completedStage(translate(room, e));
        }
        return stage.exceptionally(error -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            return translate(room, cause);
        });
    }

    private Reply translate(String room, Throwable error) {
        if (error instanceof UnknownRoomException) return unknownRoom(room);
        if (error instanceof WaitingRoomUnavailableException) {
            long retry = Math.max(1, properties.getPoll().getMin().toSeconds());
            return new Reply(503, Map.of("error", "queue_unavailable", "room", room),
                    List.of(), Map.of("Retry-After", Long.toString(retry), "Cache-Control", "no-store"));
        }
        if (error instanceof RuntimeException re) throw re;
        throw new CompletionException(error);
    }

    private static Reply unknownRoom(String room) {
        return new Reply(404, Map.of("error", "unknown_room", "room", room), List.of(), Map.of());
    }

    private ResponseCookie cookie(String name, String value, Duration maxAge) {
        FoyerProperties.Cookie c = properties.getCookie();
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(c.isSecure())
                .sameSite(c.getSameSite())
                .path(c.getPath())
                .maxAge(maxAge);
        if (c.getDomain() != null && !c.getDomain().isEmpty()) b.domain(c.getDomain());
        return b.build();
    }
}
