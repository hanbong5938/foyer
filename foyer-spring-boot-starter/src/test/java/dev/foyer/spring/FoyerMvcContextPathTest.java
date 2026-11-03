package dev.foyer.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * Servlet app mounted under {@code /app} with a nested queue prefix, overlapping protect patterns of two rooms and a
 * CORS policy: the queue endpoints stay reachable, every URL the gate hands out carries the mount point and the
 * prefix, every matched room must be admitted, preflights pass, visitor cookies are bounded.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FoyerMvcContextPathTest.App.class,
        properties = {
            "server.servlet.context-path=/app",
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
class FoyerMvcContextPathTest {

    static final String UUID_A = "0f7b2d2e-5c1a-4e6b-9a3d-1b2c3d4e5f60";
    static final AtomicInteger PROTECTED_HITS = new AtomicInteger();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        RecordingWaitingRoom waitingRoom() {
            return new RecordingWaitingRoom();
        }

        @Bean
        WebMvcConfigurer cors() {
            return new WebMvcConfigurer() {
                @Override
                public void addCorsMappings(CorsRegistry registry) {
                    registry.addMapping("/**").allowedOrigins("http://spa.example").allowedMethods("GET", "POST");
                }
            };
        }

        @RestController
        static class ProtectedController {
            @GetMapping("/protected/seats")
            Map<String, String> seats() {
                PROTECTED_HITS.incrementAndGet();
                return Map.of("ok", "true");
            }
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    RecordingWaitingRoom store;

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        store.calls.clear();
        PROTECTED_HITS.set(0);
    }

    @Test
    void queueEndpointsStayReachableUnderWildcardProtectionAndUrlsCarryTheContextPath() throws Exception {
        HttpResponse<String> entered = send("POST", "/app/queue/foyer-v2/demo/enter");
        assertThat(entered.statusCode()).isEqualTo(200);
        Map<String, Object> body = json.readValue(entered.body(), Map.class);
        assertThat(body).containsEntry("state", "ADMITTED").containsKey("token");
        assertThat(store.calls("enter")).hasSize(1);
        String token = cookie(entered, "foyer-token-demo");

        HttpResponse<String> wait = send("GET", "/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1");
        assertThat(wait.statusCode()).isEqualTo(200);
        assertThat(wait.body()).contains("base = \"/app/queue/foyer-v2\"").contains("redirect = \"/app/protected/seats?x=1\"");
        assertThat(send("GET", "/app/queue/foyer-v2/demo/wait").body()).contains("redirect = \"/app/\"");
        assertThat(send("GET", "/app/queue/foyer-v2/demo/wait?redirect=//evil.example").body()).contains("redirect = \"/app/\"");

        HttpResponse<String> redirected = send("GET", "/app/protected/seats?x=1", "Accept", "text/html");
        assertThat(redirected.statusCode()).isEqualTo(302);
        assertThat(redirected.headers().firstValue("Location"))
                .contains("/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1");

        HttpResponse<String> rejected = send("GET", "/app/protected/seats?x=1", "Accept", "application/json");
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(json.readValue(rejected.body(), Map.class))
                .containsEntry("room", "demo")
                .containsEntry("waitUrl", "/app/queue/foyer-v2/demo/wait?redirect=%2Fapp%2Fprotected%2Fseats%3Fx%3D1");
        assertThat(PROTECTED_HITS).hasValue(0);

        // What the page's script will actually fetch: base + "/" + room + "/status" and "/enter".
        String base = scriptVar(wait.body(), "base");
        assertThat(base).isEqualTo("/app/queue/foyer-v2");
        HttpResponse<String> status = send("GET", base + "/demo/status", "Accept", "application/json");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(json.readValue(status.body(), Map.class)).containsEntry("state", "NOT_IN_QUEUE");
        assertThat(send("POST", base + "/demo/enter", "Accept", "application/json").statusCode()).isEqualTo(200);

        // /protected/** is claimed by both rooms: the demo token alone is not enough.
        HttpResponse<String> secondaryEntered = send("POST", base + "/secondary/enter");
        assertThat(secondaryEntered.statusCode()).isEqualTo(200);
        String secondaryToken = cookie(secondaryEntered, "foyer-token-secondary");
        HttpResponse<String> admitted = send("GET", "/app/protected/seats?x=1",
                "Cookie", "foyer-token-demo=" + token + "; foyer-token-secondary=" + secondaryToken);
        assertThat(admitted.statusCode()).isEqualTo(200);
        assertThat(PROTECTED_HITS).hasValue(1);

        assertThat(send("POST", "/app/queue/foyer-v2/nope/enter").statusCode()).isEqualTo(404);
        assertThat(send("GET", "/app/queue/foyer-v2/nope/wait", "Accept", "text/html").statusCode()).isEqualTo(404);
        assertThat(send("GET", "/app/foyer/demo/wait", "Accept", "text/html").statusCode()).isEqualTo(302);
    }

    @Test
    void everyRoomClaimingThePathMustAdmitTheVisitor() throws Exception {
        String demo = cookie(send("POST", "/app/queue/foyer-v2/demo/enter"), "foyer-token-demo");
        String secondary = cookie(send("POST", "/app/queue/foyer-v2/secondary/enter"), "foyer-token-secondary");

        HttpResponse<String> onlyDemo = send("GET", "/app/protected/seats",
                "Accept", "application/json", "Cookie", "foyer-token-demo=" + demo);
        assertThat(onlyDemo.statusCode()).isEqualTo(429);
        assertThat(json.readValue(onlyDemo.body(), Map.class))
                .containsEntry("room", "secondary")
                .containsEntry("waitUrl", "/app/queue/foyer-v2/secondary/wait?redirect=%2Fapp%2Fprotected%2Fseats");

        HttpResponse<String> onlySecondary = send("GET", "/app/protected/seats",
                "Accept", "application/json", "Cookie", "foyer-token-secondary=" + secondary);
        assertThat(onlySecondary.statusCode()).isEqualTo(429);
        assertThat(json.readValue(onlySecondary.body(), Map.class)).containsEntry("room", "demo");

        HttpResponse<String> browser = send("GET", "/app/protected/seats",
                "Accept", "text/html", "Cookie", "foyer-token-demo=" + demo);
        assertThat(browser.statusCode()).isEqualTo(302);
        assertThat(browser.headers().firstValue("Location"))
                .contains("/app/queue/foyer-v2/secondary/wait?redirect=%2Fapp%2Fprotected%2Fseats");
        assertThat(PROTECTED_HITS).hasValue(0);

        HttpResponse<String> both = send("GET", "/app/protected/seats",
                "Accept", "application/json", "Cookie", "foyer-token-demo=" + demo + "; foyer-token-secondary=" + secondary);
        assertThat(both.statusCode()).isEqualTo(200);
        assertThat(PROTECTED_HITS).hasValue(1);

        // Outside /protected only demo's /** applies.
        HttpResponse<String> elsewhere = send("GET", "/app/other", "Accept", "application/json", "Cookie", "foyer-token-demo=" + demo);
        assertThat(elsewhere.statusCode()).isEqualTo(404);
        assertThat(send("GET", "/app/other", "Accept", "application/json", "Cookie", "foyer-token-secondary=" + secondary)
                .statusCode()).isEqualTo(429);
    }

    @Test
    void preflightsFollowTheCorsPolicyWhilePlainOptionsStaysGated() throws Exception {
        HttpResponse<String> allowed = send("OPTIONS", "/app/protected/seats",
                "Origin", "http://spa.example", "Access-Control-Request-Method", "GET");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://spa.example");
        assertThat(PROTECTED_HITS).hasValue(0);

        HttpResponse<String> forbidden = send("OPTIONS", "/app/protected/seats",
                "Origin", "http://evil.example", "Access-Control-Request-Method", "GET");
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

        HttpResponse<String> plain = send("OPTIONS", "/app/protected/seats", "Accept", "application/json");
        assertThat(plain.statusCode()).isEqualTo(429);
    }

    @Test
    void visitorCookiesAreOnlyHonouredAsCanonicalUuids() throws Exception {
        HttpResponse<String> kept = send("POST", "/app/queue/foyer-v2/demo/enter", "Cookie", "foyer-visitor=" + UUID_A);
        assertThat(kept.statusCode()).isEqualTo(200);
        assertThat(setCookies(kept)).noneMatch(c -> c.startsWith("foyer-visitor="));
        assertThat(store.calls("enter")).containsExactly("enter:demo:" + UUID_A);

        store.calls.clear();
        HttpResponse<String> left = send("DELETE", "/app/queue/foyer-v2/demo", "Cookie", "foyer-visitor=" + UUID_A);
        assertThat(left.statusCode()).isEqualTo(204);
        assertThat(setCookies(left)).isEmpty();
        assertThat(store.calls("leave")).containsExactly("leave:demo:" + UUID_A);

        for (String bad : List.of("x", UUID_A.toUpperCase(), UUID_A + "0", "a".repeat(4096), "../../etc")) {
            store.calls.clear();
            HttpResponse<String> replaced = send("POST", "/app/queue/foyer-v2/demo/enter", "Cookie", "foyer-visitor=" + bad);
            assertThat(replaced.statusCode()).isEqualTo(200);
            String issued = cookie(replaced, "foyer-visitor");
            assertThat(issued).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").isNotEqualTo(bad);
            assertThat(store.calls("enter")).containsExactly("enter:demo:" + issued);

            HttpResponse<String> status = send("GET", "/app/queue/foyer-v2/demo/status", "Cookie", "foyer-visitor=" + bad);
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(json.readValue(status.body(), Map.class)).containsEntry("state", "NOT_IN_QUEUE");
            assertThat(status.headers().firstValue("Cache-Control")).as(bad).contains("no-store");
            assertThat(send("DELETE", "/app/queue/foyer-v2/demo", "Cookie", "foyer-visitor=" + bad).statusCode()).isEqualTo(204);
            assertThat(store.calls("poll")).isEmpty();
            assertThat(store.calls("leave")).isEmpty();

            assertThat(send("GET", "/app/queue/foyer-v2/nope/status", "Cookie", "foyer-visitor=" + bad).statusCode()).isEqualTo(404);
            assertThat(send("DELETE", "/app/queue/foyer-v2/nope", "Cookie", "foyer-visitor=" + bad).statusCode()).isEqualTo(404);
        }
    }

    /**
     * {@code POST /enter} is a JSON-only contract: a cross-site HTML form, sent without a SameSite=Lax cookie,
     * must not be able to replace the shared visitor identity. Rejection happens
     * before any identity is minted or the store is touched.
     */
    @Test
    void enterRejectsNonJsonContentTypesBeforeTouchingIdentityOrStore() throws Exception {
        List<String[]> rejected = List.of(
                new String[0],
                new String[] {"Content-Type", "text/plain"},
                new String[] {"Content-Type", "application/x-www-form-urlencoded"},
                new String[] {"Content-Type", "multipart/form-data; boundary=----foyer"},
                new String[] {"Content-Type", "not a media type"},
                new String[] {"Content-Type", "*/*"},
                new String[] {"Content-Type", "application/*"},
                new String[] {"Content-Type", "application/problem+json"});
        for (String[] headers : rejected) {
            store.calls.clear();
            String[] withCookie = new String[headers.length + 2];
            System.arraycopy(headers, 0, withCookie, 0, headers.length);
            withCookie[headers.length] = "Cookie";
            withCookie[headers.length + 1] = "foyer-visitor=" + UUID_A;
            HttpResponse<String> response = raw("POST", "/app/queue/foyer-v2/demo/enter", withCookie);
            String label = headers.length == 0 ? "<none>" : headers[1];
            assertThat(response.statusCode()).as("Content-Type %s", label).isEqualTo(415);
            assertThat(json.readValue(response.body(), Map.class)).as(label)
                    .containsEntry("error", "unsupported_media_type").containsEntry("room", "demo");
            assertThat(setCookies(response)).as(label).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).as(label).contains("no-store");
            assertThat(store.calls).as(label).isEmpty();

            // Media type validation precedes room validation.
            assertThat(raw("POST", "/app/queue/foyer-v2/nope/enter", headers).statusCode()).as(label).isEqualTo(415);
        }

        for (String ok : List.of("application/json", "application/json; charset=UTF-8", "Application/JSON")) {
            store.calls.clear();
            HttpResponse<String> response = raw("POST", "/app/queue/foyer-v2/demo/enter", "Content-Type", ok);
            assertThat(response.statusCode()).as(ok).isEqualTo(200);
            assertThat(json.readValue(response.body(), Map.class)).as(ok).containsEntry("state", "ADMITTED");
            assertThat(cookie(response, "foyer-visitor")).as(ok).isNotEmpty();
            assertThat(store.calls("enter")).as(ok).hasSize(1);
        }
        assertThat(raw("POST", "/app/queue/foyer-v2/nope/enter", "Content-Type", "application/json").statusCode())
                .isEqualTo(404);
    }

    /** Cross-origin JSON clients keep working through the application's CORS policy. */
    @Test
    void allowedOriginsMayEnterWithJsonAfterPreflight() throws Exception {
        HttpResponse<String> preflight = raw("OPTIONS", "/app/queue/foyer-v2/demo/enter",
                "Origin", "http://spa.example",
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "content-type");
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("http://spa.example");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
                .contains("content-type");
        assertThat(store.calls).isEmpty();

        HttpResponse<String> entered = raw("POST", "/app/queue/foyer-v2/demo/enter",
                "Origin", "http://spa.example", "Content-Type", "application/json");
        assertThat(entered.statusCode()).isEqualTo(200);
        assertThat(entered.headers().firstValue("Access-Control-Allow-Origin")).contains("http://spa.example");
        assertThat(store.calls("enter")).hasSize(1);

        HttpResponse<String> forbidden = raw("OPTIONS", "/app/queue/foyer-v2/demo/enter",
                "Origin", "http://evil.example",
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "content-type");
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void statusWithoutAVisitorIsNeverCached() throws Exception {
        HttpResponse<String> missing = send("GET", "/app/queue/foyer-v2/demo/status");
        assertThat(missing.statusCode()).isEqualTo(200);
        assertThat(json.readValue(missing.body(), Map.class)).containsEntry("state", "NOT_IN_QUEUE");
        assertThat(missing.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(store.calls("poll")).isEmpty();
    }

    /** Sends with {@code Content-Type: application/json} on POST unless the caller supplied one. */
    private HttpResponse<String> send(String method, String path, String... headers) throws IOException, InterruptedException {
        if (method.equals("POST") && !hasHeader(headers, "Content-Type")) {
            String[] withJson = new String[headers.length + 2];
            System.arraycopy(headers, 0, withJson, 0, headers.length);
            withJson[headers.length] = "Content-Type";
            withJson[headers.length + 1] = "application/json";
            headers = withJson;
        }
        return raw(method, path, headers);
    }

    /** Exactly the given headers, nothing implied. */
    private HttpResponse<String> raw(String method, String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) b.headers(headers);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean hasHeader(String[] headers, String name) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            if (headers[i].equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie");
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return setCookies(response).stream().filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1).split(";", 2)[0]).findFirst().orElseThrow();
    }

    @Test
    void subSecondAdmissionsKeepTheirTokenCookie() throws Exception {
        HttpResponse<String> entered = raw("POST", "/app/queue/foyer-v2/flash/enter", "Content-Type", "application/json");
        assertThat(entered.statusCode()).isEqualTo(200);
        String tokenCookie = setCookies(entered).stream().filter(c -> c.startsWith("foyer-token-flash=")).findFirst().orElseThrow();
        assertThat(tokenCookie).contains("Max-Age=1");
    }

    private static String scriptVar(String html, String name) {
        Matcher m = Pattern.compile(name + " = \"([^\"]*)\"").matcher(html);
        assertThat(m.find()).as("script variable %s in wait page", name).isTrue();
        return m.group(1);
    }
}
