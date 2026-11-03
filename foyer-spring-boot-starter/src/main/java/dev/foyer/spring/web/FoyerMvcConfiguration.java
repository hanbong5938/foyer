package dev.foyer.spring.web;

import static org.springframework.web.servlet.function.RequestPredicates.DELETE;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RouterFunctions.route;

import dev.foyer.spring.FoyerProperties;
import dev.foyer.spring.gate.AdmissionGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/** Servlet wiring: functional queue endpoints plus an interceptor for {@code foyer.rooms.<id>.protect} paths. */
@Configuration(proxyBeanMethods = false)
public class FoyerMvcConfiguration {

    @Bean
    public RouterFunction<ServerResponse> foyerMvcRoutes(QueueHandler handler, FoyerProperties properties) {
        String base = properties.getBasePath();
        return route()
                .POST(base + "/{room}/enter", req -> async(handler.enter(
                        room(req), visitor(req, handler), req.headers().firstHeader(HttpHeaders.CONTENT_TYPE))))
                .GET(base + "/{room}/status", req -> async(handler.status(room(req), visitor(req, handler))))
                .DELETE(base + "/{room}", req -> async(handler.leave(room(req), visitor(req, handler))))
                .GET(base + "/{room}/wait", req -> render(handler.waitPage(
                        req.servletRequest().getContextPath(), room(req), req.param("redirect").orElse(null))))
                .build();
    }

    /**
     * Spring MVC constructs its functional request before invoking a route and rejects wildcard Content-Type
     * with IllegalArgumentException there. Validate entry requests before that normalization, using the same
     * predicate as QueueHandler. OPTIONS and valid JSON continue through application CORS processing unchanged.
     */
    @Bean
    public OncePerRequestFilter foyerEntryMediaTypeFilter(FoyerProperties properties) {
        var entry = PathPatternParser.defaultInstance.parse(properties.getBasePath() + "/{room}/enter");
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if ("POST".equals(request.getMethod())
                        && !QueueHandler.isJson(request.getHeader(HttpHeaders.CONTENT_TYPE))) {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    var match = entry.matchAndExtract(PathContainer.parsePath(path));
                    if (match != null) {
                        response.setStatus(415);
                        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                        response.getWriter().write("{\"error\":\"unsupported_media_type\",\"room\":"
                                + AdmissionGate.jsonString(match.getUriVariables().get("room")) + "}");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }

    /**
     * One interceptor per room with protected paths. The queue endpoints under {@code foyer.base-path} are always
     * excluded (a {@code /**} protect pattern must not lock visitors out of the line itself).
     */
    @Bean
    public WebMvcConfigurer foyerInterceptors(AdmissionGate gate, FoyerProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                properties.getRooms().forEach((room, cfg) -> {
                    if (!cfg.getProtect().isEmpty()) {
                        registry.addInterceptor(new FoyerInterceptor(gate, room))
                                .addPathPatterns(cfg.getProtect())
                                .excludePathPatterns(properties.getBasePath() + "/**");
                    }
                });
            }
        };
    }

    private static String room(ServerRequest req) {
        return req.pathVariable("room");
    }

    private static String visitor(ServerRequest req, QueueHandler handler) {
        Cookie c = req.cookies().getFirst(handler.visitorCookieName());
        return c == null ? null : c.getValue();
    }

    private static ServerResponse async(CompletionStage<QueueHandler.Reply> stage) {
        return ServerResponse.async(stage.thenApply(FoyerMvcConfiguration::render).toCompletableFuture());
    }

    private static ServerResponse render(QueueHandler.Reply reply) {
        ServerResponse.BodyBuilder b = ServerResponse.status(reply.status());
        reply.headers().forEach(b::header);
        reply.cookies().forEach(c -> b.header(HttpHeaders.SET_COOKIE, c.toString()));
        if (reply.body() == null) return b.build();
        if (reply.isHtml()) return b.contentType(MediaType.TEXT_HTML).body(reply.body());
        return b.contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    /** Rejects requests to protected paths unless they carry a valid admission token for the room. */
    public static final class FoyerInterceptor implements HandlerInterceptor {
        private final AdmissionGate gate;
        private final String room;

        public FoyerInterceptor(AdmissionGate gate, String room) {
            this.gate = gate;
            this.room = room;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            // CORS preflights carry no credentials by definition; Spring's CORS processing decides their fate.
            if (CorsUtils.isPreFlightRequest(request)) return true;
            String token = request.getHeader(AdmissionGate.TOKEN_HEADER);
            if (token == null && request.getCookies() != null) {
                String name = gate.tokenCookieName(room);
                for (Cookie c : request.getCookies()) {
                    if (name.equals(c.getName())) { token = c.getValue(); break; }
                }
            }
            String original = request.getQueryString() == null
                    ? request.getRequestURI() : request.getRequestURI() + "?" + request.getQueryString();
            boolean html = AdmissionGate.wantsHtml(request.getHeader(HttpHeaders.ACCEPT), request.getHeader("Sec-Fetch-Mode"));
            AdmissionGate.Decision d = gate.check(room, token, html, request.getContextPath(), original);
            switch (d.verdict()) {
                case ALLOW -> { return true; }
                case REDIRECT -> {
                    response.setStatus(HttpServletResponse.SC_FOUND);
                    response.setHeader(HttpHeaders.LOCATION, d.waitUrl());
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                }
                case REJECT -> {
                    response.setStatus(429);
                    response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(AdmissionGate.rejectBody(room, d.waitUrl()));
                }
            }
            return false;
        }
    }
}
