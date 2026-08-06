package com.company.triage.orchestration.trace;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J16/RTR-4, asserted against the <b>shipped</b> {@code application.yml} rather than a test
 * fixture — the whole point of RTR-4 is that a future {@code timeout-ms} change cannot
 * silently arm a mid-run eviction, and a fixture value would sail past exactly that change.
 *
 * <p>The failure this guards: a run that emits no steps can legitimately stay silent for a
 * full {@code timeout-ms} (twice that, with an FND-7 degrade). If the buffer TTL ever fell
 * below that window, {@link InMemoryRunTraceRegistry#lookup}'s own sweep would evict the
 * buffer it was about to read, the poll would 404 with {@code everSawData=true}, and the UI
 * would take that as the documented terminal signal and go dark <b>while the run was still
 * in flight</b>. {@code timeout-ms} has already been retuned once (90 s → 120 s, FND-69),
 * which is how two unlinked constants drift into that state in the first place.
 *
 * <p>Reads the YAML directly instead of booting a Spring context: the assertion is about the
 * committed configuration file, and a stubbed or profile-overridden context would be a
 * weaker subject than the file itself.
 */
class RunTraceRegistryTtlContractTest {

    @SuppressWarnings("unchecked")
    private static long shippedTimeoutMs() throws Exception {
        try (InputStream in = RunTraceRegistryTtlContractTest.class
                .getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("shipped application.yml must be on the test classpath").isNotNull();
            // application.yml is a multi-document file (profile blocks separated by `---`),
            // so the base value has to be found across documents rather than in doc 0.
            Long timeoutMs = null;
            for (Object doc : new Yaml().loadAll(in)) {
                if (!(doc instanceof Map<?, ?> root)) continue;
                if (!(root.get("triage") instanceof Map<?, ?> triage)) continue;
                if (!(triage.get("orchestrator") instanceof Map<?, ?> orchestrator)) continue;
                if (orchestrator.get("timeout-ms") instanceof Number n) {
                    timeoutMs = n.longValue();
                }
            }
            assertThat(timeoutMs).as("triage.orchestrator.timeout-ms must be set").isNotNull();
            return timeoutMs;
        }
    }

    @Test
    void ttlCoversTheShippedTimeoutsWorstCaseSilentWindow() throws Exception {
        long timeoutMs = shippedTimeoutMs();

        // Asserted through the Spring-wired constructor, not the static helper alone, so
        // this also fails if someone rewires the bean to stop reading TriageProperties.
        var base = com.company.triage.config.TriagePropertiesFixture.deterministic();
        var shipped = new com.company.triage.config.TriageProperties(base.engine(), base.writeback(),
                new com.company.triage.config.TriageProperties.Orchestrator(timeoutMs), base.agent(),
                base.trigger(), base.servicenow(), base.sumo(), base.gitlab());

        assertThat(new InMemoryRunTraceRegistry(shipped).ttl().toMillis())
                .as("RTR-4: TTL must cover 2 x timeout-ms (FND-7 degrade) + the writeback tail "
                        + "for the SHIPPED timeout-ms=%d — raise the TTL formula, not this bound",
                        timeoutMs)
                .isGreaterThanOrEqualTo(2 * timeoutMs + 60_000);
    }

    /**
     * RTR-4 is a link, not a retune. At the value shipped today the derived TTL is exactly
     * the 5 minutes the deleted hardcoded constant was, so this change is behaviour-preserving.
     * If someone retunes {@code timeout-ms}, this test is expected to fail and be updated
     * deliberately — that visibility is the deliverable.
     */
    @Test
    void atTodaysShippedTimeoutTheDerivedTtlIsUnchangedFromTheConstantItReplaced() throws Exception {
        assertThat(shippedTimeoutMs())
                .as("if this changed, re-check the TTL below is still what you want")
                .isEqualTo(120_000L);
        assertThat(InMemoryRunTraceRegistry.ttlFor(120_000L)).isEqualTo(Duration.ofMinutes(5));
    }
}
