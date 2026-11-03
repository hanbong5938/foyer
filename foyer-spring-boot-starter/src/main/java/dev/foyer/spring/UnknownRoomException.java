package dev.foyer.spring;

public class UnknownRoomException extends RuntimeException {
    private final String room;

    public UnknownRoomException(String room) {
        super("unknown room: " + room);
        this.room = room;
    }

    public String room() {
        return room;
    }
}
