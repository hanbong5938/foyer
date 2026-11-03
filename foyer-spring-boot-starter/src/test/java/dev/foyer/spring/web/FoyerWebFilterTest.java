package dev.foyer.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foyer.core.HmacTokenCodec;
import dev.foyer.spring.FoyerProperties;
import dev.foyer.spring.gate.AdmissionGate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;

/** Denial-time handler lookup of {@link FoyerWebFluxConfiguration.FoyerWebFilter} outside a full Boot context. */
class FoyerWebFilterTest {

    private final AtomicInteger downstream = new AtomicInteger();
    private final WebFilterChain chain = exchange -> {
        downstream.incrementAndGet();
        return Mono.empty();
    };

    @Test
    void deniedOriginRequestWithoutAnyHandlerMappingKeepsTheGateAnswerAndNoCorsHeaders() {
        MockServerWebExchange exchange = exchange();

        filter(dispatcher(List.of())).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"room\":\"demo\"");
        assertThat(downstream).hasValue(0);
    }

    @Test
    void handlerMappingErrorsPropagateInsteadOfBecomingAGateAnswer() {
        MockServerWebExchange exchange = exchange();
        IllegalStateException boom = new IllegalStateException("mapping boom");
        HandlerMapping failing = ex -> Mono.error(boom);

        assertThatThrownBy(() -> filter(dispatcher(List.of(failing))).filter(exchange, chain).block()).isSameAs(boom);

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstream).hasValue(0);
    }

    @Test
    void customWebHandlerLeavesTheGateAnswerAlone() {
        MockServerWebExchange exchange = exchange();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(WebHttpHandlerBuilder.WEB_HANDLER_BEAN_NAME, (org.springframework.web.server.WebHandler) ex -> Mono.empty());

        filter(beans).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(downstream).hasValue(0);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/protected/seats")
                .header(HttpHeaders.ORIGIN, "http://spa.example")
                .accept(MediaType.APPLICATION_JSON));
    }

    private static StaticListableBeanFactory dispatcher(List<HandlerMapping> mappings) {
        GenericApplicationContext context = new GenericApplicationContext();
        for (int i = 0; i < mappings.size(); i++) {
            HandlerMapping mapping = mappings.get(i);
            context.registerBean("mapping" + i, HandlerMapping.class, () -> mapping);
        }
        context.refresh();
        DispatcherHandler handler = new DispatcherHandler(context);
        assertThat(handler.getHandlerMappings()).hasSize(mappings.size());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(WebHttpHandlerBuilder.WEB_HANDLER_BEAN_NAME, handler);
        return beans;
    }

    private static FoyerWebFluxConfiguration.FoyerWebFilter filter(StaticListableBeanFactory beans) {
        FoyerProperties properties = new FoyerProperties();
        FoyerProperties.Room demo = new FoyerProperties.Room();
        demo.setProtect(List.of("/protected/**"));
        properties.getRooms().put("demo", demo);
        AdmissionGate gate = new AdmissionGate(new HmacTokenCodec("0123456789abcdef0123456789abcdef"), properties);
        return new FoyerWebFluxConfiguration.FoyerWebFilter(gate, properties, beans);
    }
}
