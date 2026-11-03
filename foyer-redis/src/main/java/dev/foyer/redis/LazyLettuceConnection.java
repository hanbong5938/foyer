package dev.foyer.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Connects on first use and re-attempts after a failed connect, so an application can start (and fail open)
 * while Redis is down. Once established, Lettuce's own auto-reconnect keeps the connection alive. {@link #close}
 * is terminal: later {@link #get} calls fail, and a connect still in flight at close time is closed once it
 * completes. The {@link RedisClient} itself is owned by the caller and never shut down here.
 */
public final class LazyLettuceConnection
        implements Supplier<CompletionStage<RedisScriptingAsyncCommands<String, String>>>, AutoCloseable {

    private final RedisClient client;
    private final RedisURI uri;
    /** Guarded by {@code this}. */
    private CompletableFuture<StatefulRedisConnection<String, String>> connecting;
    /** Guarded by {@code this}. */
    private boolean closed;

    public LazyLettuceConnection(RedisClient client, RedisURI uri) {
        this.client = client;
        this.uri = uri;
    }

    @Override
    public CompletionStage<RedisScriptingAsyncCommands<String, String>> get() {
        CompletableFuture<StatefulRedisConnection<String, String>> current;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Redis connection is closed"));
            current = connecting;
            if (current == null || current.isCompletedExceptionally()) {
                current = connect();
                connecting = current;
            }
        }
        return current.thenApply(StatefulRedisConnection::async);
    }

    private CompletableFuture<StatefulRedisConnection<String, String>> connect() {
        try {
            return client.connectAsync(StringCodec.UTF8, uri).toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void close() {
        CompletableFuture<StatefulRedisConnection<String, String>> current;
        synchronized (this) {
            if (closed) return;
            closed = true;
            current = connecting;
        }
        if (current != null) current.thenAccept(StatefulRedisConnection::closeAsync);
    }
}
