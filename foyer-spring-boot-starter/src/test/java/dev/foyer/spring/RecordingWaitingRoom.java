package dev.foyer.spring;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoom;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Finite in-memory store for HTTP-level tests: every visitor is admitted on {@code enter}, {@code poll} re-reports
 * the admission for known visitors, and every call is recorded as {@code op:room:visitor}.
 */
public final class RecordingWaitingRoom implements WaitingRoom {

    public final List<String> calls = new CopyOnWriteArrayList<>();
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();

    @Override
    public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
        calls.add("enter:" + room + ":" + visitorId);
        admitted.add(room + ":" + visitorId);
        return CompletableFuture.completedFuture(QueueStatus.admitted(0, Instant.now().plus(policy.tokenTtl())));
    }

    @Override
    public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
        calls.add("poll:" + room + ":" + visitorId);
        if (!admitted.contains(room + ":" + visitorId)) {
            return CompletableFuture.completedFuture(QueueStatus.notInQueue(0));
        }
        return CompletableFuture.completedFuture(QueueStatus.admitted(0, Instant.now().plus(policy.tokenTtl())));
    }

    @Override
    public CompletionStage<Void> leave(String room, String visitorId) {
        calls.add("leave:" + room + ":" + visitorId);
        admitted.remove(room + ":" + visitorId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
        return CompletableFuture.completedFuture(new RoomStats(0, admitted.size()));
    }

    public List<String> calls(String op) {
        return calls.stream().filter(c -> c.startsWith(op + ":")).toList();
    }
}
