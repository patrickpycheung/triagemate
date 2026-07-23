package com.company.triage.agent;

import com.google.adk.models.langchain4j.LangChain4j;
import dev.langchain4j.model.openai.OpenAiChatModel;

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
        String baseUrl = require("LLM_BASE_URL");   // e.g. https://llm.internal/v1
        String apiKey  = require("LLM_API_KEY");
        String model   = cfg("LLM_MODEL", "gpt-4o-mini");

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

    /** System property wins over env var (lets tests point at a local fake endpoint). */
    private static String cfg(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String require(String key) {
        String v = cfg(key, null);
        if (v == null) {
            throw new IllegalStateException("Missing required config: " + key
                    + " (set env var or -D" + key + " before running with -Padk / triage.engine=adk)");
        }
        return v;
    }
}
