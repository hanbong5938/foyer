package dev.foyer.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebFilter;

/**
 * The starter's path-based gate inside a Gateway application: a denied cross-origin request must not evaluate
 * Gateway route predicates (which may read and cache the request body that only the Gateway filter chain
 * releases), while the Gateway's own non-route endpoints still receive their CORS headers on denial.
 */
@SpringBootTest(
        classes = ProtectedPathsInGatewayTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=reactive",
                "foyer.secret=0123456789abcdef0123456789abcdef",
                "foyer.rooms.demo.rate=100",
                "foyer.rooms.demo.protect[0]=/**",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origins=http://spa.example",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-methods=GET,POST",
                "spring.main.allow-bean-definition-overriding=true",
        })
class ProtectedPathsInGatewayTest {

    static final AtomicInteger PREDICATE_CALLS = new AtomicInteger();
    static final AtomicInteger LOCAL_HITS = new AtomicInteger();
    static final AtomicReference<DataBuffer> CACHED_BODY = new AtomicReference<>();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        WaitingRoom waitingRoom() {
            return new WaitingRoom() {
                @Override
                public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
                    return CompletableFuture.completedFuture(QueueStatus.admitted(0, Instant.now().plus(policy.tokenTtl())));
                }

                @Override
                public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
                    return enter(room, visitorId, policy);
                }

                @Override
                public CompletionStage<Void> leave(String room, String visitorId) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
                    return CompletableFuture.completedFuture(new RoomStats(0, 0));
                }
            };
        }

        @Bean
        RouteLocator bodyRoute(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("body", r -> r.path("/orders/**")
                            .and().readBody(String.class, body -> {
                                PREDICATE_CALLS.incrementAndGet();
                                return true;
                            })
                            .uri("http://localhost:9"))
                    .build();
        }

        /** Observes what the exchange still carries once the whole chain, including the Foyer rejection, is done. */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter cachedBodyProbe() {
            return (exchange, chain) -> chain.filter(exchange).doFinally(signal ->
                    CACHED_BODY.set(exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR)));
        }

        /** Applications may subclass the Gateway mapping; the exclusion must still recognise it. */
        @Bean
        RoutePredicateHandlerMapping routePredicateHandlerMapping(
                FilteringWebHandler webHandler, RouteLocator routeLocator, GlobalCorsProperties cors, Environment env) {
            return new RoutePredicateHandlerMapping(webHandler, routeLocator, cors, env) {};
        }

        @Bean
        LocalController localController() {
            return new LocalController();
        }
    }

    @RestController
    static class LocalController {
        @CrossOrigin(origins = "http://spa.example")
        @GetMapping("/local/ping")
        String ping() {
            LOCAL_HITS.incrementAndGet();
            return "pong";
        }
    }

    @Value("${local.server.port}")
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
        PREDICATE_CALLS.set(0);
        LOCAL_HITS.set(0);
        CACHED_BODY.set(null);
    }

    @Test
    void deniedCrossOriginRequestDoesNotEvaluateBodyReadingRoutePredicates() {
        client.post().uri("/orders/new").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://spa.example")
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue("{\"seat\":1}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.room").isEqualTo("demo");

        assertThat(PREDICATE_CALLS).as("ReadBody predicate evaluated on a rejected request").hasValue(0);
        assertThat(CACHED_BODY.get()).as("request body cached on the exchange after rejection").isNull();
    }

    @Test
    void deniedCrossOriginRequestToAGatewayLocalEndpointStillCarriesItsCorsPolicy() {
        client.get().uri("/local/ping")
                .header(HttpHeaders.ORIGIN, "http://spa.example")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example");
        assertThat(LOCAL_HITS).hasValue(0);
    }
}
