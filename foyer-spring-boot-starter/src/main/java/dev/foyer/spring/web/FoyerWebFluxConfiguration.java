package dev.foyer.spring.web;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import dev.foyer.spring.FoyerProperties;
import dev.foyer.spring.gate.AdmissionGate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reactive wiring: functional queue endpoints plus a WebFilter for {@code foyer.rooms.<id>.protect} paths. */
@Configuration(proxyBeanMethods = false)
public class FoyerWebFluxConfiguration {

    @Bean
    public RouterFunction<ServerResponse> foyerWebFluxRoutes(QueueHandler handler, FoyerProperties properties) {
        String base = properties.getBasePath();
        return route()
                .POST(base + "/{room}/enter", req -> async(handler.enter(
                        room(req), visitor(req, handler), req.headers().firstHeader(HttpHeaders.CONTENT_TYPE))))
                .GET(base + "/{room}/status", req -> async(handler.status(room(req), visitor(req, handler))))
                .DELETE(base + "/{room}", req -> async(handler.leave(room(req), visitor(req, handler))))
                .GET(base + "/{room}/wait", req -> render(handler.waitPage(
                        req.requestPath().contextPath().value(), room(req), req.queryParam("redirect").orElse(null))))
                .build();
    }

    @Bean
    public WebFilter foyerWebFilter(AdmissionGate gate, FoyerProperties properties, BeanFactory beanFactory) {
        return new FoyerWebFilter(gate, properties, beanFactory);
    }

    private static String room(ServerRequest req) {
        return req.pathVariable("room");
    }

    private static String visitor(ServerRequest req, QueueHandler handler) {
        HttpCookie c = req.cookies().getFirst(handler.visitorCookieName());
        return c == null ? null : c.getValue();
    }

    private static Mono<ServerResponse> async(CompletionStage<QueueHandler.Reply> stage) {
        return Mono.fromCompletionStage(stage).flatMap(FoyerWebFluxConfiguration::render);
    }

    private static Mono<ServerResponse> render(QueueHandler.Reply reply) {
        ServerResponse.BodyBuilder b = ServerResponse.status(reply.status());
        reply.headers().forEach(b::header);
        reply.cookies().forEach(b::cookie);
        if (reply.body() == null) return b.build();
        if (reply.isHtml()) return b.contentType(MediaType.TEXT_HTML).bodyValue(reply.body());
        return b.contentType(MediaType.APPLICATION_JSON).bodyValue(reply.body());
    }

    /**
     * Rejects requests to protected paths unless they carry a valid admission token for every room whose
     * patterns match, in configuration order. The queue endpoints under {@code foyer.base-path} and CORS
     * preflights are never gated.
     *
     * <p>A denied cross-origin request is first run through the {@link HandlerMapping}s of the application's
     * {@link DispatcherHandler} so that the CORS policy the matched handler would have applied (global
     * {@code addCorsMappings} plus {@code @CrossOrigin}) is written onto the rejection. Only the lookup runs;
     * the handler itself is never invoked. Without a {@code DispatcherHandler} the rejection stays as is.
     */
    public static final class FoyerWebFilter implements WebFilter {
        private static final String GATEWAY_ROUTE_MAPPING =
                "org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping";
        private final AdmissionGate gate;
        private final BeanFactory beanFactory;
        private final PathPattern queueEndpoints;
        private final Map<String, List<PathPattern>> protectedPaths = new LinkedHashMap<>();

        public FoyerWebFilter(AdmissionGate gate, FoyerProperties properties, BeanFactory beanFactory) {
            this.gate = gate;
            this.beanFactory = beanFactory;
            PathPatternParser parser = PathPatternParser.defaultInstance;
            this.queueEndpoints = parser.parse(properties.getBasePath() + "/**");
            properties.getRooms().forEach((room, cfg) -> {
                if (cfg.getProtect().isEmpty()) return;
                protectedPaths.put(room, cfg.getProtect().stream().map(parser::parse).toList());
            });
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (protectedPaths.isEmpty()) return chain.filter(exchange);
            ServerHttpRequest request = exchange.getRequest();
            PathContainer path = request.getPath().pathWithinApplication();
            if (queueEndpoints.matches(path) || CorsUtils.isPreFlightRequest(request)) return chain.filter(exchange);
            for (Map.Entry<String, List<PathPattern>> e : protectedPaths.entrySet()) {
                if (!matchesAny(e.getValue(), path)) continue;
                AdmissionGate.Decision d = decide(gate, e.getKey(), request, request.getURI());
                if (!d.allowed()) return rejectWithCors(exchange, e.getKey(), d);
            }
            return chain.filter(exchange);
        }

        private Mono<Void> rejectWithCors(ServerWebExchange exchange, String room, AdmissionGate.Decision d) {
            ServerHttpResponse response = exchange.getResponse();
            if (!exchange.getRequest().getHeaders().containsHeader(HttpHeaders.ORIGIN)) {
                return reject(response, room, d);
            }
            return Mono.defer(() -> {
                List<HandlerMapping> mappings = handlerMappings();
                if (mappings == null) return reject(response, room, d);
                return Flux.fromIterable(mappings)
                        .concatMap(mapping -> mapping.getHandler(exchange))
                        .next()
                        .then(Mono.defer(() -> {
                            // AbstractHandlerMapping already answered a CORS-rejected request with 403.
                            HttpStatusCode status = response.getStatusCode();
                            if (response.isCommitted() || (status != null && status.isSameCodeAs(HttpStatus.FORBIDDEN))) {
                                return response.setComplete();
                            }
                            return reject(response, room, d);
                        }));
            });
        }

        private List<HandlerMapping> handlerMappings() {
            if (!beanFactory.containsBean(WebHttpHandlerBuilder.WEB_HANDLER_BEAN_NAME)) return null;
            Object webHandler = beanFactory.getBean(WebHttpHandlerBuilder.WEB_HANDLER_BEAN_NAME);
            if (!(webHandler instanceof DispatcherHandler dispatcher) || dispatcher.getHandlerMappings() == null) {
                return null;
            }
            return dispatcher.getHandlerMappings().stream().filter(m -> !isGatewayRouteMapping(m)).toList();
        }

        /**
         * Spring Cloud Gateway's route mapping evaluates route predicates, which may read and cache the request
         * body; that cache is only released by the Gateway filter chain, which a rejection never reaches. Gateway
         * routes are gated by the {@code Foyer} gateway filter instead, so they are not consulted here. Matched by
         * name up the class hierarchy: the starter has no Gateway dependency, and the bean may be a subclass.
         */
        private static boolean isGatewayRouteMapping(HandlerMapping mapping) {
            for (Class<?> c = mapping.getClass(); c != null; c = c.getSuperclass()) {
                if (GATEWAY_ROUTE_MAPPING.equals(c.getName())) return true;
            }
            return false;
        }

        private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
            for (PathPattern p : patterns) {
                if (p.matches(path)) return true;
            }
            return false;
        }
    }

    /** Token lookup (header, then room cookie) and verdict for a reactive request. Shared with the Gateway filter. */
    public static AdmissionGate.Decision decide(
            AdmissionGate gate, String room, ServerHttpRequest request, URI originalUri) {
        String token = request.getHeaders().getFirst(AdmissionGate.TOKEN_HEADER);
        if (token == null) {
            HttpCookie c = request.getCookies().getFirst(gate.tokenCookieName(room));
            token = c == null ? null : c.getValue();
        }
        String original = originalUri.getRawQuery() == null
                ? originalUri.getRawPath() : originalUri.getRawPath() + "?" + originalUri.getRawQuery();
        boolean html = AdmissionGate.wantsHtml(
                request.getHeaders().getFirst(HttpHeaders.ACCEPT), request.getHeaders().getFirst("Sec-Fetch-Mode"));
        return gate.check(room, token, html, request.getPath().contextPath().value(), original);
    }

    /** Writes the 302-to-wait-page or 429 answer. Shared with the Gateway filter. */
    public static Mono<Void> reject(ServerHttpResponse response, String room, AdmissionGate.Decision d) {
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        if (d.verdict() == AdmissionGate.Verdict.REDIRECT) {
            response.setStatusCode(HttpStatus.FOUND);
            response.getHeaders().set(HttpHeaders.LOCATION, d.waitUrl());
            return response.setComplete();
        }
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = AdmissionGate.rejectBody(room, d.waitUrl()).getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
