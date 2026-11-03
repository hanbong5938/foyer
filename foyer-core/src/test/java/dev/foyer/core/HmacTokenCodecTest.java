package dev.foyer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Strictness;
import com.google.gson.ToNumberPolicy;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HmacTokenCodecTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final HmacTokenCodec codec = new HmacTokenCodec(SECRET, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void roundTrip() {
        AdmissionToken token = new AdmissionToken("concert", "v1", now, now.plusSeconds(60));
        assertThat(codec.decode(codec.encode(token))).contains(token);
    }

    @Test
    void rejectsExpired() {
        AdmissionToken token = new AdmissionToken("concert", "v1", now.minusSeconds(120), now.minusSeconds(1));
        assertThat(codec.decode(codec.encode(token))).isEmpty();
    }

    @Test
    void rejectsTamperedPayloadAndForeignKey() {
        String encoded = codec.encode(new AdmissionToken("concert", "v1", now, now.plusSeconds(60)));
        String[] parts = encoded.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertThat(codec.decode(tampered)).isEmpty();

        HmacTokenCodec other = new HmacTokenCodec("fedcba9876543210fedcba9876543210", Clock.fixed(now, ZoneOffset.UTC));
        assertThat(other.decode(encoded)).isEmpty();
        assertThat(codec.decode("garbage")).isEmpty();
        assertThat(codec.decode(null)).isEmpty();
    }

    @Test
    void refusesShortSecret() {
        assertThatThrownBy(() -> new HmacTokenCodec("short")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsSubSecondExpiryAndExpiresExactlyAtTheClaimedInstant() {
        Instant issued = Instant.parse("2026-01-01T00:00:00.100Z");
        Instant until = Instant.parse("2026-01-01T00:00:00.600Z");
        AdmissionToken token = new AdmissionToken("concert", "v1", issued, until);
        String encoded = at(issued).encode(token);

        assertThat(at(issued).decode(encoded)).contains(token);
        assertThat(at(Instant.parse("2026-01-01T00:00:00.599999999Z")).decode(encoded)).contains(token);
        assertThat(at(until).decode(encoded)).isEmpty();
        assertThat(at(until.plusMillis(1)).decode(encoded)).isEmpty();
        assertThat(at(until.plusSeconds(1)).decode(encoded)).isEmpty();
    }

    @Test
    void oneMillisecondLifetimeIsHonoured() {
        Instant issued = Instant.parse("2026-01-01T00:00:00.100Z");
        AdmissionToken token = new AdmissionToken("concert", "v1", issued, issued.plusMillis(1));
        String encoded = at(issued).encode(token);
        assertThat(at(issued).decode(encoded)).contains(token);
        assertThat(at(issued.plusMillis(1)).decode(encoded)).isEmpty();
    }

    @Test
    void nanosecondTimestampsRoundTrip() {
        Instant issued = Instant.parse("2026-01-01T00:00:00.123456789Z");
        Instant until = Instant.parse("2026-01-01T00:00:01.000000001Z");
        AdmissionToken token = new AdmissionToken("concert", "v1", issued, until);
        assertThat(at(issued).decode(at(issued).encode(token))).contains(token);
        assertThat(at(until.minusNanos(1)).decode(at(issued).encode(token))).contains(token);
        assertThat(at(until).decode(at(issued).encode(token))).isEmpty();
    }

    @Test
    void negativeEpochFractionsRoundTrip() {
        Instant issued = Instant.parse("1969-12-31T23:59:59.250Z");
        Instant until = Instant.parse("1969-12-31T23:59:59.750Z");
        AdmissionToken token = new AdmissionToken("concert", "v1", issued, until);
        HmacTokenCodec then = at(Instant.parse("1969-12-31T23:59:59.500Z"));
        assertThat(then.decode(then.encode(token))).contains(token);
        assertThat(at(until).decode(then.encode(token))).isEmpty();
    }

    @Test
    void wireClaimsAreFractionalNumericDates() throws Exception {
        Instant issued = Instant.parse("2026-01-01T00:00:00.100Z");
        Instant until = Instant.parse("2026-01-01T00:00:00.600Z");
        String encoded = at(issued).encode(new AdmissionToken("concert", "v1", issued, until));
        Map<String, Object> claims = claims(SignedJWT.parse(encoded).getPayload().toString());

        assertThat(claims).containsEntry("iss", "foyer").containsEntry("sub", "v1").containsEntry("room", "concert");
        assertThat((BigDecimal) claims.get("iat")).isEqualByComparingTo(new BigDecimal("1767225600.1"));
        assertThat((BigDecimal) claims.get("exp")).isEqualByComparingTo(new BigDecimal("1767225600.6"));

        String whole = at(now).encode(new AdmissionToken("concert", "v1", now, now.plusSeconds(60)));
        assertThat((BigDecimal) claims(SignedJWT.parse(whole).getPayload().toString()).get("exp"))
                .isEqualByComparingTo(new BigDecimal("1767225660"));
    }

    @Test
    void acceptsIntegerNumericDatesFromDateBasedIssuers() throws Exception {
        JWTClaimsSet legacy = new JWTClaimsSet.Builder()
                .issuer("foyer").subject("v1").claim("room", "concert")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), legacy);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        String encoded = jwt.serialize();

        assertThat(at(now.plusSeconds(59)).decode(encoded))
                .contains(new AdmissionToken("concert", "v1", now, now.plusSeconds(60)));
        assertThat(at(now.plusSeconds(60)).decode(encoded)).isEmpty();
    }

    @Test
    void exactDecimalExpiryIsNotRoundedThroughDoubles() throws Exception {
        // 1767225600.100000001 as a double is 1767225600.1: a double-based parser would reject at the clock.
        Instant clock = Instant.parse("2026-01-01T00:00:00.100Z");
        String justAfter = sign("{\"iss\":\"foyer\",\"sub\":\"v1\",\"room\":\"concert\",\"iat\":1767225600,\"exp\":1767225600.100000001}");
        assertThat(at(clock).decode(justAfter)).hasValueSatisfying(t ->
                assertThat(t.expiresAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.100000001Z")));
        String justBefore = sign("{\"iss\":\"foyer\",\"sub\":\"v1\",\"room\":\"concert\",\"iat\":1767225600,\"exp\":1767225600.099999999}");
        assertThat(at(clock).decode(justBefore)).isEmpty();
    }

    @Test
    void malformedButCorrectlySignedPayloadsAreRejectedNotThrown() throws Exception {
        String ok = "\"iss\":\"foyer\",\"sub\":\"v1\",\"room\":\"concert\",\"iat\":1767225600";
        assertThat(codec.decode(sign("{" + ok + ",\"exp\":1767225700}"))).as("control").isPresent();
        List<String> bad = List.of(
                "{" + ok + "}",
                "{" + ok + ",\"exp\":null}",
                "{" + ok + ",\"exp\":\"1767225700\"}",
                "{\"iss\":\"foyer\",\"sub\":\"v1\",\"room\":\"concert\",\"exp\":1767225700}",
                "{\"iss\":\"foyer\",\"sub\":\"v1\",\"room\":\"concert\",\"iat\":null,\"exp\":1767225700}",
                "{\"iss\":\"foyer\",\"sub\":\"v1\",\"room\":\"concert\",\"iat\":\"1767225600\",\"exp\":1767225700}",
                "{\"iss\":\"foyer\",\"sub\":1,\"room\":\"concert\",\"iat\":1767225600,\"exp\":1767225700}",
                "{\"iss\":\"foyer\",\"sub\":\"v1\",\"room\":[\"concert\"],\"iat\":1767225600,\"exp\":1767225700}",
                "{\"iss\":\"other\",\"sub\":\"v1\",\"room\":\"concert\",\"iat\":1767225600,\"exp\":1767225700}",
                "{\"sub\":\"v1\",\"room\":\"concert\",\"iat\":1767225600,\"exp\":1767225700}",
                "{" + ok + ",\"exp\":1000000000000000000000000000000}",
                "{" + ok + ",\"exp\":-1000000000000000000000000000000}",
                "{" + ok + ",\"exp\":1e30}",
                "{" + ok + ",\"exp\":1767225700.0000000001}",
                "{" + ok + ",\"exp\":1767225700,\"exp\":1767225700}",
                "{" + ok + ",\"exp\":1767225700}{}",
                "{" + ok + ",\"exp\":1767225700} x",
                "[{" + ok + ",\"exp\":1767225700}]",
                "\"not an object\"",
                "{" + ok + ",\"exp\":NaN}",
                "{" + ok + ",\"exp\":Infinity}",
                "{" + ok + ",\"exp\":-Infinity}",
                "{" + ok + ",\"exp\":1767225700,}",
                "{" + ok + ",'exp':1767225700}",
                "{" + ok + ",\"exp\":1767225700");
        for (String payload : bad) {
            assertThat(codec.decode(sign(payload))).as(payload).isEmpty();
        }
        assertThat(codec.decode(sign("{" + ok + ",\"exp\":1767225700,\"extra\":{\"a\":[1,2,{}]}}"))).as("unknown claims are skipped").isPresent();
    }

    private static HmacTokenCodec at(Instant instant) {
        return new HmacTokenCodec(SECRET, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static String sign(String payload) throws Exception {
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        jws.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }

    /** Exact-decimal reading of a flat claims object, independent of the codec's parser. */
    private static Map<String, Object> claims(String json) throws Exception {
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                JsonToken type = reader.peek();
                claims.put(name, type == JsonToken.NUMBER ? ToNumberPolicy.BIG_DECIMAL.readNumber(reader) : reader.nextString());
            }
            reader.endObject();
            assertThat(reader.peek()).isEqualTo(JsonToken.END_DOCUMENT);
        }
        return claims;
    }

    @Test
    void backoffClampsAndScalesWithPosition() {
        AdmissionPolicy policy = AdmissionPolicy.builder().rate(10).interval(Duration.ofSeconds(1)).build();
        PollBackoff backoff = new PollBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertThat(backoff.retryAfter(0, policy)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.retryAfter(99, policy)).isEqualTo(Duration.ofSeconds(5));
        assertThat(backoff.retryAfter(100_000, policy)).isEqualTo(Duration.ofSeconds(10));
    }
}
