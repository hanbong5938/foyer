package dev.foyer.gateway;

import dev.foyer.spring.RoomRegistry;
import dev.foyer.spring.gate.AdmissionGate;
import dev.foyer.spring.web.FoyerWebFluxConfiguration;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;

/**
 * Gateway filter that only forwards requests carrying a valid admission token for the configured room.
 *
 * <pre>
 * spring.cloud.gateway.server.webflux.routes:
 *   - id: booking
 *     uri: http://booking:8080
 *     predicates: [ Path=/booking/** ]
 *     filters: [ Foyer=concert-2026 ]
 * </pre>
 *
 * Browser navigations without a token are redirected to the wait page, API calls get 429 with the wait page URL.
 * The check is stateless (signature and expiry of the token), so the queue store is never touched here.
 */
public class FoyerGatewayFilterFactory extends AbstractGatewayFilterFactory<FoyerGatewayFilterFactory.Config> {

    private final AdmissionGate gate;
    private final RoomRegistry rooms;

    public FoyerGatewayFilterFactory(AdmissionGate gate, RoomRegistry rooms) {
        super(Config.class);
        this.gate = gate;
        this.rooms = rooms;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("room");
    }

    @Override
    public GatewayFilter apply(Config config) {
        String room = config.getRoom();
        if (room == null || room.isBlank()) {
            throw new IllegalArgumentException("Foyer gateway filter: room is required, e.g. 'Foyer=<room-id>'");
        }
        if (rooms.find(room).isEmpty()) {
            throw new IllegalArgumentException("Foyer gateway filter: unknown room '" + room
                    + "'; configure it under foyer.rooms." + room + " (known rooms: " + rooms.all().keySet() + ")");
        }
        return (exchange, chain) -> {
            Set<URI> originals = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
            URI original = originals == null || originals.isEmpty()
                    ? exchange.getRequest().getURI() : originals.iterator().next();
            AdmissionGate.Decision d = FoyerWebFluxConfiguration.decide(gate, room, exchange.getRequest(), original);
            if (d.allowed()) return chain.filter(exchange);
            return FoyerWebFluxConfiguration.reject(exchange.getResponse(), room, d);
        };
    }

    public static class Config {
        /** Id of a room configured under {@code foyer.rooms}. */
        private String room;

        public String getRoom() { return room; }
        public void setRoom(String room) { this.room = room; }
    }
}
