package dev.foyer.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.StringCodec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Connects on first use and re-attempts after a failed connect, so an application can start (and fail open)
 * while Redis is down. Once established, Lettuce's own auto-reconnect keeps the connection alive. {@link #close}
 * is terminal: later {@link #get} calls fail, and a connect still in flight at close time is closed once it
 * completes. The client itself is owned by the caller and never shut down here.
 *
 * <p>Works with a standalone {@link RedisClient} or a {@link RedisClusterClient}; Foyer's keys are hash-tagged by
 * room, so every script call lands on one slot.
 */
public final class LazyLettuceConnection
        implements Supplier<CompletionStage<RedisScriptingAsyncCommands<String, String>>>, AutoCloseable {

    /** One established connection: its command interface and how to close it. */
    private record Link(RedisScriptingAsyncCommands<String, String> commands, StatefulConnection<String, String> connection) {}

    private final Supplier<CompletionStage<Link>> connector;
    /** Guarded by {@code this}. */
    private CompletableFuture<Link> connecting;
    /** Guarded by {@code this}. */
    private boolean closed;

    /** Standalone (or Sentinel, via the URI) connection. */
    public LazyLettuceConnection(RedisClient client, RedisURI uri) {
        this(() -> client.connectAsync(StringCodec.UTF8, uri), StatefulRedisConnection::async);
    }
    /** Cluster connection; node discovery and MOVED/ASK handling are Lettuce's. */
    public LazyLettuceConnection(RedisClusterClient client) {
        // connectAsync requires the topology to be loaded; refreshPartitionsAsync does that without blocking.
        this(() -> client.refreshPartitionsAsync().thenCompose(v -> client.connectAsync(StringCodec.UTF8)),
                StatefulRedisClusterConnection::async);
    }

    private <C extends StatefulConnection<String, String>> LazyLettuceConnection(
            Supplier<CompletionStage<C>> connect, Function<C, RedisScriptingAsyncCommands<String, String>> commands) {
        this.connector = () -> {
            try {
                return connect.get().thenApply(c -> new Link(commands.apply(c), c));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }

    @Override
    public CompletionStage<RedisScriptingAsyncCommands<String, String>> get() {
        CompletableFuture<Link> current;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Redis connection is closed"));
            current = connecting;
            if (current == null || current.isCompletedExceptionally()) {
                current = connector.get().toCompletableFuture();
                connecting = current;
            }
        }
        return current.thenApply(Link::commands);
    }

    @Override
    public void close() {
        CompletableFuture<Link> current;
        synchronized (this) {
            if (closed) return;
            closed = true;
            current = connecting;
        }
        if (current != null) current.thenAccept(link -> link.connection().closeAsync());
    }
}
