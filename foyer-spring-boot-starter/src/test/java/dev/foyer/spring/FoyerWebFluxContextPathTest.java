package dev.foyer.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Reactive twin of {@link FoyerMvcContextPathTest}: mounted under {@code /app}, nested prefix, two rooms, CORS. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FoyerWebFluxContextPathTest.App.class,
        properties = {
            "spring.main.web-application-type=reactive",
            "spring.webflux.base-path=/app",
            "foyer.secret=0123456789abcdef0123456789abcdef",
            "foyer.base-path=/queue/foyer-v2",
            "foyer.rooms.demo.rate=5",
            "foyer.rooms.demo.protect[0]=/**",
            "foyer.rooms.demo.protect[1]=/protected/**",
            "foyer.rooms.secondary.rate=5",
            "foyer.rooms.secondary.protect[0]=/protected/**",
            "foyer.rooms.flash.rate=5",
            "foyer.rooms.flash.token-ttl=500ms",
        })
class FoyerWebFluxContextPathTest {

    static final String UUID_A = "0f7b2d2e-5c1a-4e6b-9a3d-1b2c3d4e5f60";
    static final AtomicInteger PROTECTED_HITS = new AtomicInteger();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AnnotatedController.class)
    static class App {
        @Bean
        RecordingWaitingRoom waitingRoom() {
            return new RecordingWaitingRoom();
        }

        @Bean
        WebFluxConfigurer cors() {
            return new WebFluxConfigurer() {
                @Override
                public void addCorsMappings(CorsRegistry registry) {
                    registry.addMapping("/**").allowedOrigins("http://spa.example").allowedMethods("GET", "POST");
                }
            };
        }

        @Bean
        RouterFunction<ServerResponse> protectedRoute() {
            return RouterFunctions.route().GET("/protected/seats", r -> {
                PROTECTED_HITS.incrementAndGet();
                return ServerResponse.ok().bodyValue("seats");
            }).build();
        }
    }

    /** Handler-level CORS policy that the global registry does not grant. */
    @RestController
    static class AnnotatedController {
        @CrossOrigin(origins = "http://partner.example")
        @GetMapping("/protected/annotated")
        String annotated() {
            PROTECTED_HITS.incrementAndGet();
            return "annotated";
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingWaitingRoom store;

    WebTestClient web;

    @BeforeEach
    void reset() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        store.calls.clear();
        PROTECTED_HITS.set(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queueEndpointsStayReachableUnderWildcardProtectionAndUrlsCarryTheContextPath() {
        EntityExchangeResult<Map> entered = web.post().uri("/app/queue/foyer-v2/demo/enter").contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk().expectBody(Map.class).returnResult();
        assertThat(entered.getResponseBody()).containsEntry("state", "ADMITTED").containsKey("token");
        assertThat(store.calls("enter")).hasSize(1);
        String token = entered.getResponseCookies().getFirst("foyer-token-demo").getValue();

        String page = web.get().uri(URI.create("http://localhost:" + port
                        + "/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1")).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(page).contains("base = \"/app/queue/foyer-v2\"").contains("redirect = \"/app/protected/seats?x=1\"");
        assertThat(body("/app/queue/foyer-v2/demo/wait")).contains("redirect = \"/app/\"");
        assertThat(body("/app/queue/foyer-v2/demo/wait?redirect=//evil.example")).contains("redirect = \"/app/\"");

        web.get().uri("/app/protected/seats?x=1").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION,
                        "/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1");
        web.get().uri("/app/protected/seats?x=1").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.room").isEqualTo("demo")
                .jsonPath("$.waitUrl").isEqualTo("/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1");
        assertThat(PROTECTED_HITS).hasValue(0);

        String base = scriptVar(page, "base");
        assertThat(base).isEqualTo("/app/queue/foyer-v2");
        web.get().uri(base + "/demo/status").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("NOT_IN_QUEUE");
        web.post().uri(base + "/demo/enter").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk();

        // /protected/** is claimed by both rooms: the demo token alone is not enough.
        String secondaryToken = web.post().uri(base + "/secondary/enter").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseCookies().getFirst("foyer-token-secondary").getValue();
        web.get().uri("/app/protected/seats?x=1")
                .cookie("foyer-token-demo", token).cookie("foyer-token-secondary", secondaryToken).exchange()
                .expectStatus().isOk().expectBody(String.class).isEqualTo("seats");
        assertThat(PROTECTED_HITS).hasValue(1);

        web.post().uri("/app/queue/foyer-v2/nope/enter").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isNotFound();
        web.get().uri("/app/queue/foyer-v2/nope/wait").accept(MediaType.TEXT_HTML).exchange().expectStatus().isNotFound();
        web.get().uri("/app/foyer/demo/wait").accept(MediaType.TEXT_HTML).exchange().expectStatus().isFound();
    }

    @Test
    void everyRoomClaimingThePathMustAdmitTheVisitor() {
        String demo = web.post().uri("/app/queue/foyer-v2/demo/enter").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseCookies().getFirst("foyer-token-demo").getValue();
        String secondary = web.post().uri("/app/queue/foyer-v2/secondary/enter").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseCookies().getFirst("foyer-token-secondary").getValue();

        web.get().uri("/app/protected/seats").accept(MediaType.APPLICATION_JSON).cookie("foyer-token-demo", demo).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.room").isEqualTo("secondary")
                .jsonPath("$.waitUrl").isEqualTo("/app/queue/foyer-v2/secondary/wait?redirect=%2Fapp%2Fprotected%2Fseats");

        web.get().uri("/app/protected/seats").accept(MediaType.APPLICATION_JSON).cookie("foyer-token-secondary", secondary)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody().jsonPath("$.room").isEqualTo("demo");

        web.get().uri("/app/protected/seats").accept(MediaType.TEXT_HTML).cookie("foyer-token-demo", demo).exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION,
                        "/app/queue/foyer-v2/secondary/wait?redirect=%2Fapp%2Fprotected%2Fseats");
        assertThat(PROTECTED_HITS).hasValue(0);

        web.get().uri("/app/protected/seats").accept(MediaType.APPLICATION_JSON)
                .cookie("foyer-token-demo", demo).cookie("foyer-token-secondary", secondary).exchange()
                .expectStatus().isOk().expectBody(String.class).isEqualTo("seats");
        assertThat(PROTECTED_HITS).hasValue(1);

        // Outside /protected only demo's /** applies.
        web.get().uri("/app/other").accept(MediaType.APPLICATION_JSON).cookie("foyer-token-demo", demo).exchange()
                .expectStatus().isNotFound();
        web.get().uri("/app/other").accept(MediaType.APPLICATION_JSON).cookie("foyer-token-secondary", secondary).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void preflightsFollowTheCorsPolicyWhilePlainOptionsStaysGated() {
        web.options().uri("/app/protected/seats")
                .header(HttpHeaders.ORIGIN, "http://spa.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example");
        assertThat(PROTECTED_HITS).hasValue(0);

        web.options().uri("/app/protected/seats")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);

        web.options().uri("/app/protected/seats").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void deniedCrossOriginRequestsCarryTheApplicationCorsPolicy() {
        web.get().uri("/app/protected/seats?x=1").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://spa.example").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example")
                .expectHeader().value(HttpHeaders.VARY, v -> assertThat(v).contains("Origin"))
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.room").isEqualTo("demo")
                .jsonPath("$.waitUrl").isEqualTo("/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1");
        web.get().uri("/app/protected/seats?x=1").accept(MediaType.TEXT_HTML)
                .header(HttpHeaders.ORIGIN, "http://spa.example").exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION,
                        "/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example");
        assertThat(PROTECTED_HITS).hasValue(0);

        web.get().uri("/app/protected/seats?x=1").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://evil.example").exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(PROTECTED_HITS).hasValue(0);

        String demo = web.post().uri("/app/queue/foyer-v2/demo/enter").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseCookies().getFirst("foyer-token-demo").getValue();
        String secondary = web.post().uri("/app/queue/foyer-v2/secondary/enter").contentType(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseCookies().getFirst("foyer-token-secondary").getValue();
        web.get().uri("/app/protected/seats?x=1").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://spa.example").cookie("foyer-token-demo", demo).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example")
                .expectBody().jsonPath("$.room").isEqualTo("secondary");
        assertThat(PROTECTED_HITS).hasValue(0);
        web.get().uri("/app/protected/seats?x=1").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://spa.example")
                .cookie("foyer-token-demo", demo).cookie("foyer-token-secondary", secondary).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example")
                .expectBody(String.class).isEqualTo("seats");
        assertThat(PROTECTED_HITS).hasValue(1);
    }

    @Test
    void deniedCrossOriginRequestsHonourHandlerLevelCorsPolicy() {
        web.get().uri("/app/protected/annotated").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://partner.example").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://partner.example")
                .expectBody()
                .jsonPath("$.room").isEqualTo("demo")
                .jsonPath("$.waitUrl").isEqualTo("/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fannotated");
        web.get().uri("/app/protected/seats").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://partner.example").exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(PROTECTED_HITS).hasValue(0);
    }

    @Test
    void visitorCookiesAreOnlyHonouredAsCanonicalUuids() {
        web.post().uri("/app/queue/foyer-v2/demo/enter").contentType(MediaType.APPLICATION_JSON).cookie("foyer-visitor", UUID_A).exchange()
                .expectStatus().isOk().expectCookie().doesNotExist("foyer-visitor");
        assertThat(store.calls("enter")).containsExactly("enter:demo:" + UUID_A);

        store.calls.clear();
        web.delete().uri("/app/queue/foyer-v2/demo").cookie("foyer-visitor", UUID_A).exchange()
                .expectStatus().isNoContent().expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
        assertThat(store.calls("leave")).containsExactly("leave:demo:" + UUID_A);

        for (String bad : List.of("x", UUID_A.toUpperCase(), UUID_A + "0", "a".repeat(4096), "../../etc")) {
            store.calls.clear();
            ResponseCookie issued = web.post().uri("/app/queue/foyer-v2/demo/enter").contentType(MediaType.APPLICATION_JSON).cookie("foyer-visitor", bad).exchange()
                    .expectStatus().isOk().expectCookie().exists("foyer-visitor")
                    .expectBody().returnResult().getResponseCookies().getFirst("foyer-visitor");
            assertThat(issued.getValue())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").isNotEqualTo(bad);
            assertThat(store.calls("enter")).containsExactly("enter:demo:" + issued.getValue());

            web.get().uri("/app/queue/foyer-v2/demo/status").cookie("foyer-visitor", bad).exchange()
                    .expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                    .expectBody().jsonPath("$.state").isEqualTo("NOT_IN_QUEUE");
            web.delete().uri("/app/queue/foyer-v2/demo").cookie("foyer-visitor", bad).exchange().expectStatus().isNoContent();
            assertThat(store.calls("poll")).isEmpty();
            assertThat(store.calls("leave")).isEmpty();

            web.get().uri("/app/queue/foyer-v2/nope/status").cookie("foyer-visitor", bad).exchange().expectStatus().isNotFound();
            web.delete().uri("/app/queue/foyer-v2/nope").cookie("foyer-visitor", bad).exchange().expectStatus().isNotFound();
        }
    }

    @Test
    void enterRejectsNonJsonBeforeIdentityOrStoreMutation() {
        for (String type : List.of("", "text/plain", "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=----foyer", "not a media type", "*/*",
                "application/*", "application/problem+json")) {
            for (String room : List.of("demo", "nope")) {
                store.calls.clear();
                var request = web.post().uri("/app/queue/foyer-v2/" + room + "/enter");
                if (!type.isEmpty()) request.header(HttpHeaders.CONTENT_TYPE, type);
                request.exchange().expectStatus().isEqualTo(415)
                        .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                        .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                        .expectBody().jsonPath("$.error").isEqualTo("unsupported_media_type")
                        .jsonPath("$.room").isEqualTo(room);
                assertThat(store.calls).as("Content-Type %s, room %s", type, room).isEmpty();
            }
        }
        for (String type : List.of("application/json", "application/json; charset=UTF-8", "Application/JSON")) {
            store.calls.clear();
            web.post().uri("/app/queue/foyer-v2/demo/enter").header(HttpHeaders.CONTENT_TYPE, type)
                    .exchange().expectStatus().isOk().expectCookie().exists("foyer-visitor")
                    .expectBody().jsonPath("$.state").isEqualTo("ADMITTED");
            assertThat(store.calls("enter")).hasSize(1);
        }
    }

    @Test
    void jsonEntryUsesApplicationCorsPolicy() {
        web.options().uri("/app/queue/foyer-v2/demo/enter")
                .header(HttpHeaders.ORIGIN, "http://spa.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type");
        assertThat(store.calls).isEmpty();
        web.post().uri("/app/queue/foyer-v2/demo/enter").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, "http://spa.example").exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://spa.example")
                .expectBody().jsonPath("$.state").isEqualTo("ADMITTED");
        web.options().uri("/app/queue/foyer-v2/demo/enter")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(store.calls("enter")).hasSize(1);
    }

    @Test
    void missingVisitorStatusIsNotCached() {
        web.get().uri("/app/queue/foyer-v2/demo/status").exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody().jsonPath("$.state").isEqualTo("NOT_IN_QUEUE");
        assertThat(store.calls("poll")).isEmpty();
    }

    private String body(String uri) {
        return web.get().uri(uri).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private static String scriptVar(String html, String name) {
        Matcher m = Pattern.compile(name + " = \"([^\"]*)\"").matcher(html);
        assertThat(m.find()).as("script variable %s in wait page", name).isTrue();
        return m.group(1);
    }

    @Test
    void subSecondAdmissionsKeepTheirTokenCookie() {
        web.post().uri("/app/queue/foyer-v2/flash/enter").contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk()
                .expectCookie().maxAge("foyer-token-flash", java.time.Duration.ofSeconds(1));
    }
}
