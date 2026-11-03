package dev.foyer.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.foyer.core.HmacTokenCodec;
import dev.foyer.spring.gate.AdmissionGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FoyerMvcIntegrationTest.App.class,
        properties = {
            "foyer.secret=0123456789abcdef0123456789abcdef",
            "foyer.rooms.demo.rate=1",
            "foyer.rooms.demo.interval=1h",
            "foyer.rooms.demo.token-ttl=10m",
            "foyer.rooms.demo.queue-ttl=1h",
            "foyer.rooms.demo.protect[0]=/protected/**",
            "foyer.rooms.leave-a.rate=1",
            "foyer.rooms.leave-a.interval=1h",
            "foyer.rooms.leave-a.token-ttl=10m",
            "foyer.rooms.leave-a.queue-ttl=1h",
            "foyer.rooms.leave-b.rate=1",
            "foyer.rooms.leave-b.interval=1h",
            "foyer.rooms.leave-b.token-ttl=10m",
            "foyer.rooms.leave-b.queue-ttl=1h",
            "foyer.redis.key-prefix=mvctest",
        })
class FoyerMvcIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("foyer.redis.uri", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @RestController
        static class ProtectedController {
            @GetMapping("/protected/seats")
            Map<String, String> seats() {
                return Map.of("ok", "true");
            }
        }
    }

    @LocalServerPort
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    MeterRegistry meters;

    RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                            throws java.io.IOException {
                        super.prepareConnection(connection, httpMethod);
                        connection.setInstanceFollowRedirects(false);
                    }
                })
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void endToEnd() {
        RestClient http = client();

        // 1. First visitor enters an empty room: admitted immediately, visitor + token cookies set.
        ResponseEntity<Map> first = http.post().uri("/foyer/demo/enter").contentType(MediaType.APPLICATION_JSON)
                .retrieve().toEntity(Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("state", "ADMITTED").containsKey("token");
        List<String> cookies = first.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("foyer-visitor=").contains("HttpOnly"))
                .anySatisfy(c -> assertThat(c).startsWith("foyer-token-demo=").contains("HttpOnly"));
        String visitorCookie = cookieHeader(cookies, "foyer-visitor");
        String tokenCookie = cookieHeader(cookies, "foyer-token-demo");

        // 2. Protected path: cookie → 200; header → 200; nothing → 302 (browser) / 429 (API).
        assertThat(http.get().uri("/protected/seats").header(HttpHeaders.COOKIE, tokenCookie)
                .retrieve().toEntity(String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.get().uri("/protected/seats").header(AdmissionGate.TOKEN_HEADER, (String) first.getBody().get("token"))
                .retrieve().toEntity(String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> redirected = http.get().uri("/protected/seats?x=1").accept(MediaType.TEXT_HTML)
                .retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class);
        assertThat(redirected.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirected.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/foyer/demo/wait?redirect=%2Fprotected%2Fseats%3Fx%3D1");

        ResponseEntity<Map> rejected = http.get().uri("/protected/seats").accept(MediaType.APPLICATION_JSON)
                .retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(rejected.getBody()).containsEntry("error", "waiting_room").containsEntry("room", "demo")
                .containsEntry("waitUrl", "/foyer/demo/wait?redirect=%2Fprotected%2Fseats");

        // A token for another room signed with the same secret is not accepted.
        String foreign = new HmacTokenCodec("0123456789abcdef0123456789abcdef")
                .encode(new dev.foyer.core.AdmissionToken("other", "v", java.time.Instant.now(),
                        java.time.Instant.now().plusSeconds(60)));
        assertThat(http.get().uri("/protected/seats").header(AdmissionGate.TOKEN_HEADER, foreign)
                .retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // 3. Second visitor waits (rate 1/h, bucket spent): position 0, server-driven retry interval.
        ResponseEntity<Map> second = http.post().uri("/foyer/demo/enter").contentType(MediaType.APPLICATION_JSON)
                .retrieve().toEntity(Map.class);
        assertThat(second.getBody()).containsEntry("state", "WAITING").containsEntry("position", 0)
                .containsEntry("queueSize", 1);
        assertThat(((Number) second.getBody().get("retryAfterMs")).longValue()).isEqualTo(10_000);
        String secondVisitor = cookieHeader(second.getHeaders().get(HttpHeaders.SET_COOKIE), "foyer-visitor");

        ResponseEntity<Map> polled = http.get().uri("/foyer/demo/status").header(HttpHeaders.COOKIE, secondVisitor)
                .retrieve().toEntity(Map.class);
        assertThat(polled.getBody()).containsEntry("state", "WAITING").containsEntry("position", 0);
        assertThat(polled.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();

        // 4. Admitted visitor polling again gets the admission (and token) re-issued.
        ResponseEntity<Map> again = http.get().uri("/foyer/demo/status").header(HttpHeaders.COOKIE, visitorCookie)
                .retrieve().toEntity(Map.class);
        assertThat(again.getBody()).containsEntry("state", "ADMITTED").containsKey("token");

        // 5. Status without a visitor cookie, leave, unknown room, wait page.
        assertThat(http.get().uri("/foyer/demo/status").retrieve().toEntity(Map.class).getBody())
                .containsEntry("state", "NOT_IN_QUEUE");
        ResponseEntity<Void> left = http.delete().uri("/foyer/demo").header(HttpHeaders.COOKIE, secondVisitor)
                .retrieve().toBodilessEntity();
        assertThat(left.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.get().uri("/foyer/demo/status").header(HttpHeaders.COOKIE, secondVisitor)
                .retrieve().toEntity(Map.class).getBody()).containsEntry("state", "NOT_IN_QUEUE");

        ResponseEntity<Map> unknown = http.post().uri("/foyer/nope/enter").contentType(MediaType.APPLICATION_JSON)
                .retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody()).containsEntry("error", "unknown_room");

        ResponseEntity<String> wait = http.get().uri(URI.create("http://localhost:" + port
                        + "/foyer/demo/wait?redirect=%2Fprotected%2Fseats"))
                .retrieve().toEntity(String.class);
        assertThat(wait.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wait.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(wait.getBody()).contains("var room = \"demo\"").contains("redirect = \"/protected/seats\"");
        // open-redirect attempts fall back to "/"
        assertThat(http.get().uri(URI.create("http://localhost:" + port + "/foyer/demo/wait?redirect=//evil.example"))
                .retrieve().toEntity(String.class).getBody()).contains("redirect = \"/\"");

        // 6. Metrics. Gauges sample the store asynchronously: the first read starts the sample, a later one sees it.
        assertThat(meters.get("foyer.enter").tag("room", "demo").counter().count()).isGreaterThanOrEqualTo(2);
        assertThat(meters.get("foyer.admit").tag("room", "demo").tag("fail_open", "false").counter().count())
                .isGreaterThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(meters.get("foyer.queue.waiting").tag("room", "demo").gauge().value()).isEqualTo(0.0);
            assertThat(meters.get("foyer.queue.active").tag("room", "demo").gauge().value()).isEqualTo(1.0);
        });
    }

    /**
     * DELETE is room-local: a browser that is waiting in two rooms and leaves one keeps its visitor identity and its
     * place in the other. Cookies flow through a real cookie jar so a {@code Set-Cookie} that expires the visitor
     * cookie is observed the way a browser would observe it.
     */
    @Test
    void leavingOneRoomPreservesOtherQueueMembership() throws Exception {
        // Spend both rooms' single hourly admission so later visitors wait.
        Visitor occupant = new Visitor();
        assertThat(occupant.enter("leave-a")).containsEntry("state", "ADMITTED");
        assertThat(occupant.enter("leave-b")).containsEntry("state", "ADMITTED");

        Visitor subject = new Visitor();
        assertThat(subject.enter("leave-a")).containsEntry("state", "WAITING").containsEntry("position", 0);
        assertThat(subject.enter("leave-b")).containsEntry("state", "WAITING").containsEntry("position", 0);
        String identity = subject.visitorCookie();
        assertThat(identity).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        HttpResponse<String> left = subject.send("DELETE", "/foyer/leave-a");
        assertThat(left.statusCode()).isEqualTo(204);
        assertThat(left.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(subject.visitorCookie()).isEqualTo(identity);
        assertThat(subject.status("leave-b")).containsEntry("state", "WAITING").containsEntry("position", 0);
        assertThat(subject.status("leave-a")).containsEntry("state", "NOT_IN_QUEUE");

        // Re-entering the left room enrolls behind a newer visitor under the same identity.
        Visitor third = new Visitor();
        assertThat(third.enter("leave-a")).containsEntry("state", "WAITING").containsEntry("position", 0);
        assertThat(subject.enter("leave-a")).containsEntry("state", "WAITING").containsEntry("position", 1);
        assertThat(subject.visitorCookie()).isEqualTo(identity);
        assertThat(subject.status("leave-b")).containsEntry("state", "WAITING").containsEntry("position", 0);
    }

    /** One browser: its own cookie jar, never a hand-built {@code Cookie} header. */
    private final class Visitor {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(jar)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        Map<String, Object> enter(String room) throws Exception {
            return body(send("POST", "/foyer/" + room + "/enter"));
        }

        Map<String, Object> status(String room) throws Exception {
            return body(send("GET", "/foyer/" + room + "/status"));
        }

        HttpResponse<String> send(String method, String path) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10));
            if ("POST".equals(method)) {
                request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        /** Current {@code foyer-visitor} value in the jar, or {@code null} once a response has expired it. */
        String visitorCookie() {
            return jar.getCookieStore().get(URI.create("http://localhost:" + port + "/")).stream()
                    .filter(c -> c.getName().equals("foyer-visitor")).map(HttpCookie::getValue).findFirst().orElse(null);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> body(HttpResponse<String> response) {
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readValue(response.body(), Map.class);
        }
    }

    private static String cookieHeader(List<String> setCookies, String name) {
        return setCookies.stream().filter(c -> c.startsWith(name + "=")).map(c -> c.split(";", 2)[0]).findFirst()
                .orElseThrow();
    }
}
