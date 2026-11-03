package dev.foyer.core;

import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.ToNumberPolicy;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * HS256 JWT codec. Claims: {@code iss=foyer}, {@code sub=visitorId}, {@code room}, {@code iat}, {@code exp}.
 * Any standard JWT library can verify the token with the shared secret, which lets non-JVM services sit behind
 * the same gate.
 *
 * <p>{@code iat} and {@code exp} are RFC 7519 NumericDates with the fractional seconds of the {@link Instant}
 * preserved, so a token expires exactly when the store's admission does. Claims are read with an exact decimal
 * parser: a {@code double} or {@code java.util.Date} round-trip would silently move the deadline.
 */
public final class HmacTokenCodec implements AdmissionTokenCodec {

    public static final String ISSUER = "foyer";
    public static final String ROOM_CLAIM = "room";
    private static final int MIN_SECRET_BYTES = 32;
    private static final int NANOS_PER_SECOND_SCALE = 9;
    private static final BigDecimal MIN_NUMERIC_DATE = decimal(Instant.MIN);
    private static final BigDecimal MAX_NUMERIC_DATE = decimal(Instant.MAX);

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final Clock clock;

    public HmacTokenCodec(String secret) {
        this(secret, Clock.systemUTC());
    }

    public HmacTokenCodec(String secret, Clock clock) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("token secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        try {
            this.signer = new MACSigner(key);
            this.verifier = new MACVerifier(key);
        } catch (JOSEException e) {
            throw new IllegalArgumentException("invalid token secret", e);
        }
        this.clock = clock;
    }

    @Override
    public String encode(AdmissionToken token) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(token.visitorId())
                .claim(ROOM_CLAIM, token.room())
                .claim("iat", numericDate(token.issuedAt()))
                .claim("exp", numericDate(token.expiresAt()))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign admission token", e);
        }
        return jwt.serialize();
    }

    @Override
    public Optional<AdmissionToken> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return Optional.empty();
        try {
            SignedJWT jwt = SignedJWT.parse(encoded);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                return Optional.empty();
            }
            Claims claims = readClaims(jwt.getPayload().toString());
            if (claims == null || !ISSUER.equals(claims.iss) || claims.sub == null || claims.room == null
                    || claims.iat == null || claims.exp == null) {
                return Optional.empty();
            }
            Instant expiresAt = instant(claims.exp);
            if (!expiresAt.isAfter(clock.instant())) return Optional.empty();
            return Optional.of(new AdmissionToken(claims.room, claims.sub, instant(claims.iat), expiresAt));
        } catch (ParseException | JOSEException | IOException | JsonParseException | ArithmeticException
                 | DateTimeException e) {
            return Optional.empty();
        }
    }

    private static final class Claims {
        String iss;
        String sub;
        String room;
        BigDecimal iat;
        BigDecimal exp;
    }

    /**
     * Strict single-object parse. Wrong-typed or {@code null} claims are left unset so the caller rejects them;
     * duplicate names, a non-object root and trailing content are rejected outright.
     */
    private static Claims readClaims(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return null;
            reader.beginObject();
            Claims claims = new Claims();
            Set<String> seen = new HashSet<>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) return null;
                switch (name) {
                    case "iss" -> claims.iss = string(reader);
                    case "sub" -> claims.sub = string(reader);
                    case ROOM_CLAIM -> claims.room = string(reader);
                    case "iat" -> claims.iat = number(reader);
                    case "exp" -> claims.exp = number(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT ? claims : null;
        }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            reader.skipValue();
            return null;
        }
        return reader.nextString();
    }

    private static BigDecimal number(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) {
            reader.skipValue();
            return null;
        }
        return (BigDecimal) ToNumberPolicy.BIG_DECIMAL.readNumber(reader);
    }

    /** RFC 7519 NumericDate: whole seconds as a long, otherwise the exact decimal seconds. */
    private static Number numericDate(Instant instant) {
        return instant.getNano() == 0 ? (Number) instant.getEpochSecond() : decimal(instant);
    }

    private static BigDecimal decimal(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), NANOS_PER_SECOND_SCALE));
    }

    private static Instant instant(BigDecimal numericDate) {
        if (numericDate.compareTo(MIN_NUMERIC_DATE) < 0 || numericDate.compareTo(MAX_NUMERIC_DATE) > 0) {
            throw new DateTimeException("NumericDate outside the Instant range: " + numericDate);
        }
        BigDecimal[] parts = numericDate.divideAndRemainder(BigDecimal.ONE);
        long seconds = parts[0].longValueExact();
        int nanos = parts[1].movePointRight(NANOS_PER_SECOND_SCALE).intValueExact();
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
