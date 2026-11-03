package dev.foyer.core;

import java.time.Instant;

/**
 * Proof that a visitor was admitted to a room. Self-contained: verifying it needs the signing key only, no
 * queue store round trip, so protected services and gateways stay up when the store is down.
 */
public record AdmissionToken(String room, String visitorId, Instant issuedAt, Instant expiresAt) {}
