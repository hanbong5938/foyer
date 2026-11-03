package dev.foyer.spring;

import dev.foyer.core.AdmissionPolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Configured rooms, validated once at startup. */
public final class RoomRegistry {

    private static final Pattern ROOM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Map<String, AdmissionPolicy> policies;

    public RoomRegistry(FoyerProperties properties) {
        Map<String, AdmissionPolicy> map = new LinkedHashMap<>();
        properties.getRooms().forEach((id, room) -> {
            if (!ROOM_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("foyer.rooms: invalid room id '" + id + "' (allowed: [A-Za-z0-9_-])");
            }
            AdmissionPolicy policy = AdmissionPolicy.builder()
                    .rate(room.getRate())
                    .interval(room.getInterval())
                    .burst(room.getBurst() == null ? room.getRate() : room.getBurst())
                    .maxActive(room.getMaxActive())
                    .tokenTtl(room.getTokenTtl())
                    .queueTtl(room.getQueueTtl())
                    .build();
            if (!policy.queueTtl().isZero() && policy.queueTtl().compareTo(properties.getPoll().getMax()) <= 0) {
                throw new IllegalArgumentException("foyer.rooms." + id + ".queue-ttl must exceed foyer.poll.max, "
                        + "otherwise well-behaved clients get evicted between polls");
            }
            map.put(id, policy);
        });
        this.policies = Collections.unmodifiableMap(map);
    }

    public Optional<AdmissionPolicy> find(String room) {
        return Optional.ofNullable(policies.get(room));
    }

    public AdmissionPolicy require(String room) {
        return find(room).orElseThrow(() -> new UnknownRoomException(room));
    }

    public Map<String, AdmissionPolicy> all() {
        return policies;
    }
}
