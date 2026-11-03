package dev.foyer.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.AdmissionToken;
import dev.foyer.core.HmacTokenCodec;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoom;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        classes = FoyerGatewayFilterFactoryTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=reactive",
                "foyer.secret=" + FoyerGatewayFilterFactoryTest.SECRET,
                "foyer.rooms.demo.rate=100",
                "spring.cloud.gateway.server.webflux.routes[0].id=protected",
                // Unreachable on purpose: only forwarded requests ever try to connect.
                "spring.cloud.gateway.server.webflux.routes[0].uri=http://localhost:9",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/protected/**",
                "spring.cloud.gateway.server.webflux.routes[0].filters[0]=Foyer=demo",
                // Rewriting filters run before Foyer: the wait page must still return to the client's original path.
                "spring.cloud.gateway.server.webflux.routes[1].id=stripped",
                "spring.cloud.gateway.server.webflux.routes[1].uri=http://localhost:9",
                "spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/stripped/**",
                "spring.cloud.gateway.server.webflux.routes[1].filters[0]=StripPrefix=1",
                "spring.cloud.gateway.server.webflux.routes[1].filters[1]=Foyer=demo",
                "spring.cloud.gateway.server.webflux.routes[2].id=rewritten",
                "spring.cloud.gateway.server.webflux.routes[2].uri=http://localhost:9",
                "spring.cloud.gateway.server.webflux.routes[2].predicates[0]=Path=/rewritten/**",
                "spring.cloud.gateway.server.webflux.routes[2].filters[0]=RewritePath=/rewritten/(?<segment>.*), /backend/$\\{segment}",
                "spring.cloud.gateway.server.webflux.routes[2].filters[1]=Foyer=demo",
                "spring.cloud.gateway.server.webflux.routes[3].id=multi",
                "spring.cloud.gateway.server.webflux.routes[3].uri=http://localhost:9",
                "spring.cloud.gateway.server.webflux.routes[3].predicates[0]=Path=/multi/**",
                "spring.cloud.gateway.server.webflux.routes[3].filters[0]=StripPrefix=1",
                "spring.cloud.gateway.server.webflux.routes[3].filters[1]=RewritePath=/(?<segment>.*), /backend/$\\{segment}",
                "spring.cloud.gateway.server.webflux.routes[3].filters[2]=Foyer=demo",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origins=http://spa.example",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-methods=GET",
        })
class FoyerGatewayFilterFactoryTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Value("${local.server.port}")
    int port;

    @Autowired
    FoyerGatewayFilterFactory factory;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void browserWithoutTokenIsRedirectedToWaitPage() {
        client.get().uri("/protected/x?a=1")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/foyer/demo/wait?redirect=%2Fprotected%2Fx%3Fa%3D1")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void apiCallWithoutTokenGets429() {
        client.get().uri("/protected/x?a=1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("waiting_room")
                .jsonPath("$.room").isEqualTo("demo")
                .jsonPath("$.waitUrl").isEqualTo("/foyer/demo/wait?redirect=%2Fprotected%2Fx%3Fa%3D1");
    }

    @Test
    void validTokenInCookieIsForwarded() {
        client.get().uri("/protected/x")
                .accept(MediaType.APPLICATION_JSON)
                .cookie("foyer-token-demo", token("demo"))
                .exchange()
                // Reached the routing filter: the unreachable upstream yields a server error, not a Foyer rejection.
                .expectStatus().is5xxServerError();
    }

    @Test
    void validTokenInHeaderIsForwarded() {
        client.get().uri("/protected/x")
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Foyer-Token", token("demo"))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void tokenForAnotherRoomIsRejected() {
        client.get().uri("/protected/x")
                .accept(MediaType.APPLICATION_JSON)
                .cookie("foyer-token-demo", token("other"))
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void allowedCorsPreflightIsAnsweredBeforeTheFilter() {
        client.options().uri("/protected/x")
                .header(HttpHeaders.ORIGIN, "http://spa.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example");
    }

    @Test
    void forbiddenOriginPreflightIsRejectedWithoutApproval() {
        client.options().uri("/protected/x")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void plainOptionsWithoutTokenIsStillGated() {
        client.options().uri("/protected/x")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void unknownRoomFailsAtApplyTime() {
        FoyerGatewayFilterFactory.Config config = new FoyerGatewayFilterFactory.Config();
        config.setRoom("nope");
        assertThatThrownBy(() -> factory.apply(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown room 'nope'")
                .hasMessageContaining("foyer.rooms.nope");
        assertThat(factory.shortcutFieldOrder()).containsExactly("room");
        assertThat(factory.name()).isEqualTo("Foyer");
    }

    @Test
    void strippedRouteRedirectsBrowserToOriginalPath() {
        client.get().uri("/stripped/seats?id=7")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/foyer/demo/wait?redirect=%2Fstripped%2Fseats%3Fid%3D7")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void strippedRouteApiRejectionCarriesOriginalWaitUrl() {
        client.get().uri("/stripped/seats?id=7")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.room").isEqualTo("demo")
                .jsonPath("$.waitUrl").isEqualTo("/foyer/demo/wait?redirect=%2Fstripped%2Fseats%3Fid%3D7");
    }

    @Test
    void strippedRouteKeepsRawEncodedQuery() {
        // A URI (not a template) so the client sends the percent-escapes verbatim instead of re-encoding them.
        client.get().uri(URI.create("http://localhost:" + port + "/stripped/seats?q=a%20b%2Fc&id=7"))
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION,
                        "/foyer/demo/wait?redirect=%2Fstripped%2Fseats%3Fq%3Da%2520b%252Fc%26id%3D7");
    }

    @Test
    void rewrittenRouteRedirectsBrowserToOriginalPath() {
        client.get().uri("/rewritten/seats?id=7")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/foyer/demo/wait?redirect=%2Frewritten%2Fseats%3Fid%3D7");
    }

    @Test
    void rewrittenRouteApiRejectionCarriesOriginalWaitUrl() {
        client.get().uri("/rewritten/seats?id=7")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.waitUrl").isEqualTo("/foyer/demo/wait?redirect=%2Frewritten%2Fseats%3Fid%3D7");
    }

    @Test
    void multipleRewritesReturnToEarliestOriginalPath() {
        // StripPrefix then RewritePath each record a URI; the client's own request is the first one.
        client.get().uri("/multi/seats?id=7")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/foyer/demo/wait?redirect=%2Fmulti%2Fseats%3Fid%3D7");
        client.get().uri("/multi/seats?id=7")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.waitUrl").isEqualTo("/foyer/demo/wait?redirect=%2Fmulti%2Fseats%3Fid%3D7");
    }

    @Test
    void validTokenOnStrippedRouteIsForwarded() {
        client.get().uri("/stripped/seats?id=7")
                .accept(MediaType.APPLICATION_JSON)
                .cookie("foyer-token-demo", token("demo"))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    private static String token(String room) {
        Instant now = Instant.now();
        return new HmacTokenCodec(SECRET).encode(new AdmissionToken(room, "visitor-1", now, now.plusSeconds(300)));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** Keeps the starter off Redis: the gate is stateless and these tests never touch the queue. */
        @Bean
        WaitingRoom waitingRoom() {
            return new WaitingRoom() {
                @Override
                public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<Void> leave(String room, String visitorId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
                    return CompletableFuture.completedFuture(new RoomStats(0, 0));
                }
            };
        }
    }
}
