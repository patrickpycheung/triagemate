package com.company.triage.gateway.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.SumoGateway;

/**
 * Wires the fixture layer.
 *
 * <p>The {@link FixtureStore} bean always exists — the mocks read recordings through it on
 * every offline run. The RECORDER only exists when {@code triage.record.enabled=true}, and
 * it wraps gateways via a {@link BeanPostProcessor} rather than {@code @Primary}
 * alternatives, for one reason: the app already has exactly one bean per gateway interface
 * selected by {@code triage.connectors.*}, and a decorator that injects the interface it
 * also publishes is a self-reference. Post-processing sidesteps that entirely and keeps
 * the connector-selection logic in one place instead of two.
 *
 * <p>Only {@code com.company.triage.gateway.real} beans are wrapped. Recording a mock would
 * dutifully capture the hand-written demo data and write it back out as though it were
 * evidence from the real estate — the precise confusion fixtures exist to end — so that
 * case logs a warning and wraps nothing.
 */
@Configuration
public class FixtureConfig {

    private static final Logger log = LoggerFactory.getLogger(FixtureConfig.class);

    @Bean
    public FixtureStore fixtureStore(
            @org.springframework.beans.factory.annotation.Value("${triage.record.dir:}") String dir) {
        return new FixtureStore(dir);
    }

    /**
     * Always present, because BOTH directions need it. Recording latches the incident so
     * Confluence/Sumo/GitLab answers get filed under the right ticket; replay latches it so
     * those same mocks know which bundle to read. One bean, one latch, symmetric.
     */
    @Bean
    public FixtureSession fixtureSession() {
        return new FixtureSession();
    }

    @Bean
    @ConditionalOnProperty(name = "triage.record.enabled", havingValue = "true")
    public static BeanPostProcessor gatewayRecorder(ObjectProvider<FixtureStore> store,
                                                    ObjectProvider<FixtureSession> session) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name)
                    throws BeansException {
                boolean isReal = bean.getClass().getName()
                        .startsWith("com.company.triage.gateway.real.");
                if (!(bean instanceof ServiceNowGateway || bean instanceof ConfluenceGateway
                        || bean instanceof SumoGateway || bean instanceof GitLabGateway)) {
                    return bean;
                }
                if (!isReal) {
                    log.warn("record: {} is not a real connector — NOT recording it. Recording a "
                            + "mock would capture demo data as if it were real evidence. Run with "
                            + "--spring.profiles.active=real (or triage.connectors.*=real).", name);
                    return bean;
                }
                FixtureStore s = store.getObject();
                FixtureSession sess = session.getObject();
                log.info("record: wrapping {} — responses will be written to fixtures/", name);
                if (bean instanceof ServiceNowGateway g) {
                    return new RecordingGateways.ServiceNow(g, s, sess);
                }
                if (bean instanceof ConfluenceGateway g) {
                    return new RecordingGateways.Confluence(g, s, sess);
                }
                if (bean instanceof SumoGateway g) {
                    return new RecordingGateways.Sumo(g, s, sess);
                }
                return new RecordingGateways.GitLab((GitLabGateway) bean, s, sess);
            }
        };
    }
}
