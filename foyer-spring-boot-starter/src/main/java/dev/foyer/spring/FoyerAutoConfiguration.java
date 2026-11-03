package dev.foyer.spring;

import dev.foyer.core.AdmissionTokenCodec;
import dev.foyer.core.HmacTokenCodec;
import dev.foyer.core.PollBackoff;
import dev.foyer.core.WaitingRoom;
import dev.foyer.redis.LazyLettuceConnection;
import dev.foyer.redis.LettuceWaitingRoom;
import dev.foyer.spring.gate.AdmissionGate;
import dev.foyer.spring.metrics.FoyerMetrics;
import dev.foyer.spring.web.FoyerMvcConfiguration;
import dev.foyer.spring.web.FoyerWebFluxConfiguration;
import dev.foyer.spring.web.QueueHandler;
import dev.foyer.spring.web.WaitPage;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@EnableConfigurationProperties(FoyerProperties.class)
public class FoyerAutoConfiguration {

    /**
     * Foyer's own clock. Not a default autowire candidate, so an application {@code Clock} bean keeps resolving
     * by type; override by declaring a bean named {@code foyerClock}.
     */
    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "foyerClock")
    public Clock foyerClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoomRegistry foyerRoomRegistry(FoyerProperties properties) {
        return new RoomRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdmissionTokenCodec foyerTokenCodec(FoyerProperties properties, @Qualifier("foyerClock") Clock foyerClock) {
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            throw new IllegalStateException("foyer.secret is required (at least 32 characters)");
        }
        return new HmacTokenCodec(properties.getSecret(), foyerClock);
    }

    /** Only when the application brings no {@link WaitingRoom} of its own. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(WaitingRoom.class)
    static class RedisConfiguration {

        /** Cluster client when {@code foyer.redis.cluster-nodes} is set, else a standalone client for {@code foyer.redis.uri}. */
        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean(AbstractRedisClient.class)
        public AbstractRedisClient foyerRedisClient(FoyerProperties properties) {
            List<String> nodes = properties.getRedis().getClusterNodes();
            if (nodes.isEmpty()) return RedisClient.create();
            return RedisClusterClient.create(nodes.stream().map(RedisURI::create).toList());
        }

        /**
         * Connects lazily so the application starts (and fails open) even while Redis is down. Always registered;
         * {@link #foyerWaitingRoom} prefers a user-provided {@code StatefulRedisConnection<String, String>} and
         * leaves this holder unused (never connected) when one exists.
         */
        @Bean(destroyMethod = "close")
        public LazyLettuceConnection foyerRedisConnection(AbstractRedisClient client, FoyerProperties properties) {
            if (client instanceof RedisClusterClient cluster) return new LazyLettuceConnection(cluster);
            if (client instanceof RedisClient standalone) {
                return new LazyLettuceConnection(standalone, RedisURI.create(properties.getRedis().getUri()));
            }
            throw new IllegalStateException("unsupported Redis client " + client.getClass().getName()
                    + "; provide a StatefulRedisConnection<String,String> or WaitingRoom bean instead");
        }

        @Bean
        public WaitingRoom foyerWaitingRoom(
                ObjectProvider<StatefulRedisConnection<String, String>> connection,
                ObjectProvider<LazyLettuceConnection> lazy,
                FoyerProperties properties,
                @Qualifier("foyerClock") Clock foyerClock) {
            PollBackoff backoff = new PollBackoff(properties.getPoll().getMin(), properties.getPoll().getMax());
            String prefix = properties.getRedis().getKeyPrefix();
            StatefulRedisConnection<String, String> provided = connection.getIfAvailable();
            if (provided != null) {
                return new LettuceWaitingRoom(provided.async(), foyerClock, backoff, prefix);
            }
            return new LettuceWaitingRoom(lazy.getObject(), foyerClock, backoff, prefix);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public FoyerService foyerService(
            WaitingRoom waitingRoom,
            AdmissionTokenCodec codec,
            RoomRegistry rooms,
            FoyerProperties properties,
            @Qualifier("foyerClock") Clock foyerClock,
            ObjectProvider<FoyerEventListener> listener) {
        return new FoyerService(waitingRoom, codec, rooms, properties.isFailOpen(), foyerClock,
                listener.getIfAvailable(() -> FoyerEventListener.NONE));
    }

    @Bean
    @ConditionalOnMissingBean
    public AdmissionGate foyerAdmissionGate(AdmissionTokenCodec codec, FoyerProperties properties) {
        return new AdmissionGate(codec, properties);
    }

    /** Only when Spring Web is present: {@link QueueHandler} replies carry {@code ResponseCookie}s. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.http.ResponseCookie")
    static class QueueHandlerConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public QueueHandler foyerQueueHandler(
                FoyerService service, FoyerProperties properties, AdmissionGate gate, @Qualifier("foyerClock") Clock foyerClock) {
            return new QueueHandler(service, properties, gate, new WaitPage(properties.getBasePath()), foyerClock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean(FoyerEventListener.class)
        public FoyerMetrics foyerMetrics(
                ObjectProvider<MeterRegistry> registry, WaitingRoom waitingRoom, RoomRegistry rooms,
                @Qualifier("foyerClock") Clock foyerClock) {
            return new FoyerMetrics(registry, waitingRoom, rooms, foyerClock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.function.RouterFunction")
    @Import(FoyerMvcConfiguration.class)
    static class MvcConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.server.RouterFunction")
    @Import(FoyerWebFluxConfiguration.class)
    static class WebFluxConfiguration {}
}
