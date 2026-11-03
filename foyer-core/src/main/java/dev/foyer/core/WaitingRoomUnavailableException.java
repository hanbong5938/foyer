package dev.foyer.core;

/** The queue store (e.g. Redis) could not be reached or answered with an error. */
public class WaitingRoomUnavailableException extends RuntimeException {
    public WaitingRoomUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
