package com.company.triage.agent;

import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Builds the ADK model backend (J2) from the enterprise OpenAI-compatible endpoint.
 *
 * <p>Primary route: {@code google-adk-langchain4j} wrapping a LangChain4j
 * {@code OpenAiChatModel}. Fallback (if this route fights the endpoint at JS-1b):
 * add {@code google-adk-spring-ai} and wrap a Spring AI {@code ChatModel} instead —
 * nothing else in the app changes.
 *
 * <p>JS-1b: confirm the enterprise endpoint is genuinely OpenAI-compatible
 * (base URL path, auth header, streaming) and pin the wrapper class names against
 * the ADK 1.7.0 javadoc.
 */
public final class AdkModelFactory {

    private AdkModelFactory() {}

    public static LangChain4j fromEnv() {
        String baseUrl = require("LLM_BASE_URL", "triage.integrations.llm.base-url");
        String apiKey  = require("LLM_API_KEY",  "triage.integrations.llm.api-key");
        // No default on purpose. A silent fallback to a mini model is the FND-8 failure
        // class: D1's pitch is "a high Copilot-served model, on rails" and D3's contrast
        // asserts it is the SAME frontier model Copilot CLI runs — both are false on a mini
        // model, and nothing on screen would say so. Fail loudly instead.
        String model   = require("LLM_MODEL", "triage.integrations.llm.model");

        OpenAiChatModel chat = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.0)          // deterministic-ish triage
                .build();

        return LangChain4j.builder()
                .chatModel(chat)
                .modelName(model)
                .build();
    }

    /**
     * Resolve one setting, most-specific first: system property (lets tests point at a
     * local fake) → env var → {@code secrets.properties} (the same Java properties file
     * the Spring connectors read). {@code envKey} is the {@code LLM_*} style; {@code propKey}
     * is the Spring dotted key ({@code triage.integrations.llm.*}).
     */
    private static String cfg(String envKey, String propKey, String def) {
        String v = System.getProperty(envKey);
        if (blank(v)) v = System.getenv(envKey);
        if (blank(v)) v = secrets().getProperty(propKey);
        return blank(v) ? def : v;
    }

    private static String require(String envKey, String propKey) {
        String v = cfg(envKey, propKey, null);
        if (v == null) {
            throw new IllegalStateException("Missing required config: " + envKey
                    + " — set it in secrets.properties as " + propKey
                    + ", or as an env var / -D" + envKey
                    + " before running with -Padk / triage.engine=adk");
        }
        return v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // Loaded once. Best-effort: absent/unreadable file → empty (offline demo, or LLM
    // config supplied via env/system properties instead).
    private static volatile Properties cached;

    private static Properties secrets() {
        Properties p = cached;
        if (p == null) {
            p = new Properties();
            Path file = Path.of("secrets.properties");
            if (Files.isReadable(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
            cached = p;
        }
        return p;
    }
}
