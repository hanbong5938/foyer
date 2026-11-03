package dev.foyer.gateway;

import dev.foyer.spring.FoyerAutoConfiguration;
import dev.foyer.spring.RoomRegistry;
import dev.foyer.spring.gate.AdmissionGate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.context.annotation.Bean;

/** Registers the {@code Foyer} gateway filter ({@code filters: - Foyer=<room-id>}). */
@AutoConfiguration(after = FoyerAutoConfiguration.class)
@ConditionalOnClass(GatewayFilterFactory.class)
public class FoyerGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FoyerGatewayFilterFactory foyerGatewayFilterFactory(AdmissionGate gate, RoomRegistry rooms) {
        return new FoyerGatewayFilterFactory(gate, rooms);
    }
}
