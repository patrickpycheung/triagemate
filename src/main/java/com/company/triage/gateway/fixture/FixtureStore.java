package com.company.triage.gateway.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recorded gateway responses, captured from a REAL run and replayed in mock mode.
 *
 * <p>Why this exists: the mock gateways used to serve hand-written demo data invented
 * from nothing. It was internally consistent but bore no relation to the real estate,
 * so "it works in mock mode" said nothing about whether it works. A fixture is the
 * actual bytes a real connector returned for a real incident, so the mocked run is the
 * real run minus the network.
 *
 * <p>Layout — one file per gateway, per incident:
 * <pre>
 *   fixtures/INC0010015/servicenow.json
 *   fixtures/INC0010015/confluence.json
 *   fixtures/INC0010015/sumo.json
 *   fixtures/INC0010015/gitlab.json
 * </pre>
 *
 * <p>Each file is {@code {"method|key": &lt;recorded result JSON&gt;}}. The key is a
 * NORMALISED form of the call arguments (see {@link FixtureKeys}) — deliberately not the
 * raw arguments, because some of them (Sumo's from/to window) move every run and would
 * make every replay a miss.
 *
 * <p>Lookup degrades in one documented step: exact key, then — if the method was recorded
 * exactly once — that sole recording. The fallback is what makes a fixture survive a
 * slightly reworded query (e.g. the deterministic engine tweaking its Confluence CQL)
 * instead of silently returning nothing, which is the FND-85 shape: an empty result that
 * reads identically to a genuine "no matches". A miss is logged at WARN, never swallowed.
 */
public class FixtureStore {

    private static final Logger log = LoggerFactory.getLogger(FixtureStore.class);

    /** Where recordings are written, and where the mocks read them from on disk first. */
    public static final String DEFAULT_DIR = "src/main/resources/fixtures";
    /** Classpath fallback, so a packaged jar still replays without the source tree. */
    private static final String CLASSPATH_PREFIX = "fixtures/";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;
    /** incident -> gateway -> (key -> raw JSON). Loaded lazily, cached per incident. */
    private final Map<String, Map<String, Map<String, Object>>> cache = new ConcurrentHashMap<>();

    public FixtureStore(String dir) {
        this.root = Path.of(dir == null || dir.isBlank() ? DEFAULT_DIR : dir);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * A store that holds nothing and never will — every lookup misses, so callers take their
     * legacy path. Used by the mocks' no-arg constructors, which is what the unit tests
     * instantiate: those tests assert the hand-written J7 dataset's behaviour, and they must
     * keep asserting exactly that whether or not a fixture bundle happens to be on the
     * classpath. Wiring them to the live store would make unrelated tests start passing or
     * failing based on which incidents someone recorded last.
     */
    public static FixtureStore none() {
        return new FixtureStore(DEFAULT_DIR) {
            @Override
            public <T> Optional<T> find(String i, String g, String m, String k, TypeReference<T> t) {
                return Optional.empty();
            }

            @Override
            public boolean has(String incident, String gateway) {
                return false;
            }
        };
    }

    // ---------------------------------------------------------------- replay

    /**
     * The recorded result for {@code method} + {@code key}, deserialised as {@code type}.
     * Empty when nothing was recorded — the caller decides whether that means "fall back
     * to the legacy demo data" or "genuinely no results".
     */
    public <T> Optional<T> find(String incident, String gateway, String method, String key,
                                TypeReference<T> type) {
        Map<String, Object> calls = load(incident, gateway);
        if (calls.isEmpty()) return Optional.empty();

        String exact = method + "|" + key;
        Object hit = calls.get(exact);

        if (hit == null) {
            List<String> sameMethod = calls.keySet().stream()
                    .filter(k -> k.equals(method) || k.startsWith(method + "|"))
                    .toList();
            if (sameMethod.size() == 1) {
                log.debug("fixture: {}/{} key miss for '{}', using the sole recording '{}'",
                        incident, gateway, exact, sameMethod.get(0));
                hit = calls.get(sameMethod.get(0));
            } else {
                log.warn("fixture: {}/{} has no recording for '{}' ({} candidate(s) for that "
                        + "method) — falling through to the caller's default",
                        incident, gateway, exact, sameMethod.size());
                return Optional.empty();
            }
        }
        try {
            JavaType javaType = MAPPER.getTypeFactory().constructType(type);
            return Optional.ofNullable(MAPPER.convertValue(hit, javaType));
        } catch (RuntimeException e) {
            // A fixture that cannot be read back into its model is a broken fixture, not a
            // reason to kill the run: say so loudly and let the caller's default stand.
            log.warn("fixture: {}/{} recording '{}' could not be deserialised ({}) — ignoring it",
                    incident, gateway, exact, e.toString());
            return Optional.empty();
        }
    }

    /** True when any recording exists for this incident + gateway. */
    public boolean has(String incident, String gateway) {
        return !load(incident, gateway).isEmpty();
    }

    /**
     * True when ANY gateway was recorded for this incident — i.e. this is a recorded
     * incident, whether or not the gateway you are asking on behalf of got captured.
     *
     * <p>This is the guard that stops a partial capture from being filled in with fiction.
     * The first real capture of INC0010015 (a Delivery Hazards ticket) got ServiceNow,
     * Confluence and Sumo but not GitLab, whose corp-network endpoint 403s from off-network
     * machines. A gateway that falls back to the legacy demo data on a missing bundle would
     * then have answered that run with {@code payment_service.py:44} — a confident code
     * citation, from an unrelated invented repo, attached to a real incident. That is the
     * FND-8 class in one line.
     *
     * <p>So the rule is: legacy demo data is reachable only for an incident with NO
     * recordings at all (INC0010005 / INC0010009). For a recorded incident a missing bundle
     * means "this connector was not captured", which replays as empty — the same shape the
     * engine already handles when a connector legitimately finds nothing.
     */
    /**
     * Throws when this incident was recorded but {@code gateway} was NOT captured.
     *
     * <p>An uncaptured connector must replay as "could not search", never as "searched and
     * found nothing" — the distinction {@link com.company.triage.gateway.GatewayUnavailableException}
     * exists to preserve. GitLab is the live case: its endpoint 403s from any machine outside
     * the corp perimeter, so an off-network capture legitimately has no gitlab.json. Replaying
     * that as an empty result would put "no code matched" in the report when the truth is that
     * nothing was ever asked — the exact collapse that hid a broken Confluence base URL for a
     * day. Throwing instead reproduces the real run's trace row verbatim.
     */
    public void requireCapturedOrUnavailable(String incident, String gateway, String systemName) {
        if (hasIncident(incident) && !has(incident, gateway)) {
            throw new com.company.triage.gateway.GatewayUnavailableException(systemName,
                    new IllegalStateException(
                            "no " + gateway + " fixture recorded for " + incident
                                    + " — re-run bin/record-fixtures.sh " + incident
                                    + " from a machine that can reach it"));
        }
    }

    public boolean hasIncident(String incident) {
        return has(incident, "servicenow") || has(incident, "confluence")
                || has(incident, "sumo") || has(incident, "gitlab");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(String incident, String gateway) {
        if (incident == null || incident.isBlank()) return Map.of();
        Map<String, Map<String, Object>> byGateway =
                cache.computeIfAbsent(incident.toUpperCase(), k -> new ConcurrentHashMap<>());
        return byGateway.computeIfAbsent(gateway, g -> {
            Path onDisk = root.resolve(incident.toUpperCase()).resolve(g + ".json");
            try {
                if (Files.isReadable(onDisk)) {
                    return MAPPER.readValue(Files.readString(onDisk),
                            new TypeReference<LinkedHashMap<String, Object>>() {});
                }
                String cp = CLASSPATH_PREFIX + incident.toUpperCase() + "/" + g + ".json";
                try (InputStream in = FixtureStore.class.getClassLoader().getResourceAsStream(cp)) {
                    if (in != null) {
                        return MAPPER.readValue(in,
                                new TypeReference<LinkedHashMap<String, Object>>() {});
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("fixture: could not read {} ({})", onDisk, e.toString());
            }
            return Map.of();
        });
    }

    // --------------------------------------------------------------- record

    /**
     * Appends one call to the on-disk bundle, read-modify-write so a run that makes
     * several calls to the same gateway accumulates rather than overwriting. Recording is
     * a dev-time action driven by one operator on one machine, so the coarse-grained
     * synchronisation here is sufficient and the simplicity is worth more than throughput.
     */
    public synchronized void record(String incident, String gateway, String method, String key,
                                    Object result) {
        Path dir = root.resolve(incident.toUpperCase());
        Path file = dir.resolve(gateway + ".json");
        try {
            Files.createDirectories(dir);
            Map<String, Object> calls = new LinkedHashMap<>();
            if (Files.isReadable(file)) {
                calls.putAll(MAPPER.readValue(Files.readString(file),
                        new TypeReference<LinkedHashMap<String, Object>>() {}));
            }
            calls.put(method + "|" + key, result == null ? new ArrayList<>() : result);
            Files.writeString(file, MAPPER.writeValueAsString(calls));
            cache.remove(incident.toUpperCase());
            log.info("fixture: recorded {}#{}|{} → {}", gateway, method, key, file);
        } catch (IOException | RuntimeException e) {
            // Recording must never break the real run it is observing.
            log.warn("fixture: FAILED to record {}#{}|{} ({})", gateway, method, key, e.toString());
        }
    }
}
