package dev.foyer.core;

import java.util.Optional;

public interface AdmissionTokenCodec {

    String encode(AdmissionToken token);

    /** Empty when the token is malformed, tampered with, or expired. Room matching is the caller's job. */
    Optional<AdmissionToken> decode(String encoded);
}
