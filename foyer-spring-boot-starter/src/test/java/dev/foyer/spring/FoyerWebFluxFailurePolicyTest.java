package dev.foyer.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Reactive wiring against an unreachable Redis: the app must start, and the failure policy must apply. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FoyerWebFluxFailurePolicyTest.App.class,
        properties = {
            "spring.main.web-application-type=reactive",
            "foyer.secret=0123456789abcdef0123456789abcdef",
            "foyer.redis.uri=redis://localhost:1",
            "foyer.rooms.demo.rate=5",
            "foyer.rooms.demo.protect[0]=/protected/**",
        })
class FoyerWebFluxFailurePolicyTest {

    @LocalServerPort
    int port;

    WebTestClient web;

    @BeforeEach
    void client() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        RouterFunction<ServerResponse> protectedRoute() {
            return RouterFunctions.route().GET("/protected/seats", r -> ServerResponse.ok().bodyValue("seats")).build();
        }
    }

    @Nested
    @TestPropertySource(properties = "foyer.fail-open=true")
    class FailOpen {
        @Test
        @SuppressWarnings("unchecked")
        void admitsWithAFlaggedTokenWhenTheStoreIsDown() {
            Map<String, Object> body = web.post().uri("/foyer/demo/enter").contentType(MediaType.APPLICATION_JSON)
                    .exchange()
                    .expectStatus().isOk()
                    .expectCookie().exists("foyer-token-demo")
                    .expectBody(Map.class).returnResult().getResponseBody();
            assertThat(body).containsEntry("state", "ADMITTED").containsEntry("failOpen", true).containsKey("token");

            web.get().uri("/protected/seats").cookie("foyer-token-demo", (String) body.get("token")).exchange()
                    .expectStatus().isOk().expectBody(String.class).isEqualTo("seats");
            web.get().uri("/protected/seats?a=1").accept(MediaType.TEXT_HTML).exchange()
                    .expectStatus().isFound()
                    .expectHeader().valueEquals(HttpHeaders.LOCATION, "/foyer/demo/wait?redirect=%2Fprotected%2Fseats%3Fa%3D1");
            web.get().uri("/protected/seats").accept(MediaType.APPLICATION_JSON).exchange()
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectBody().jsonPath("$.error").isEqualTo("waiting_room").jsonPath("$.room").isEqualTo("demo");
            web.get().uri("/foyer/demo/wait").exchange().expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
        }
    }

    @Nested
    @TestPropertySource(properties = {"foyer.fail-open=false", "foyer.poll.min=2s"})
    class FailClosed {
        @Test
        void answers503WithRetryAfterWhenTheStoreIsDown() {
            web.post().uri("/foyer/demo/enter").contentType(MediaType.APPLICATION_JSON).exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
                    .expectCookie().doesNotExist("foyer-token-demo")
                    .expectBody().jsonPath("$.error").isEqualTo("queue_unavailable");
            // A well-formed visitor id reaches the store; a malformed one would be answered NOT_IN_QUEUE without it.
            web.get().uri("/foyer/demo/status").cookie("foyer-visitor", "0f7b2d2e-5c1a-4e6b-9a3d-1b2c3d4e5f60").exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
