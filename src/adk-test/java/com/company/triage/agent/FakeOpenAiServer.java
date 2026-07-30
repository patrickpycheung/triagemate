package com.company.triage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal fake OpenAI-compatible Chat Completions endpoint for the in-session JS-1b
 * live round-trip proof (no real LLM needed). Deterministic two-turn script:
 *   turn 1 (no tool results yet) → ask the model to call get_incident
 *   turn 2 (a tool-role message present) → return the final J4 JSON report
 * This exercises the full ADK loop: tool schema → tool_call → tool execution →
 * result fed back → final content parsed into DiagnosisReport.
 */
final class FakeOpenAiServer implements AutoCloseable {

    private static final ObjectMapper M = new ObjectMapper();
    private final HttpServer server;

    private FakeOpenAiServer(HttpServer server) { this.server = server; }

    static FakeOpenAiServer start() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", exchange -> {
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            // Second turn iff our prior tool_call id has been echoed back (robust to
            // however the client formats the tool-result role).
            boolean toolResultsPresent = body.contains("call_1");
            String json = toolResultsPresent ? finalResponse() : toolCallResponse();
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        s.start();
        return new FakeOpenAiServer(s);
    }

    int port() { return server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); }

    private static String toolCallResponse() {
        Map<String, Object> fn = Map.of(
                "name", "get_incident",
                "arguments", "{\"incidentNumber\":\"INC0012345\"}");
        Map<String, Object> toolCall = Map.of(
                "id", "call_1", "type", "function", "function", fn);
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", List.of(toolCall));
        return completion(message, "tool_calls");
    }

    private static String finalResponse() {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", J4_JSON);
        return completion(message, "stop");
    }

    private static String completion(Map<String, Object> message, String finishReason) {
        Map<String, Object> choice = Map.of("index", 0, "message", message, "finish_reason", finishReason);
        Map<String, Object> resp = Map.of(
                "id", "chatcmpl-fake", "object", "chat.completion", "created", 1700000000,
                "model", "fake", "choices", List.of(choice),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
        try { return M.writeValueAsString(resp); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) { return in.readAllBytes(); }
    }

    /** A valid J4 DiagnosisReport payload the agent "produces" on turn 2. */
    private static final String J4_JSON = """
        {
          "incidentNumber": "INC0012345",
          "generatedAt": "2026-07-23T20:00:00+10:00",
          "reportedSymptom": "Checkout order submission intermittently fails with a server error.",
          "affectedFunction": "Order submission (checkout)",
          "environment": "Production",
          "identifiers": {"correlationId": "INC-ORD-4471", "errorCode": null, "orderId": "INC-ORD-4471"},
          "candidateSystems": [
            {"name": "Payment Service", "confidence": 0.86, "evidenceRefs": ["e-log", "e-code"]}
          ],
          "suggestedAssignment": {"group": "Payments Platform Support", "confidence": "MEDIUM", "evidenceRefs": ["e-cmdb"]},
          "evidence": [
            {"id": "e-log", "source": "sumo", "summary": "PAYMENT_RECONCILE_MISMATCH ...", "link": "prod/payment"},
            {"id": "e-code", "source": "gitlab", "summary": "emitted at payment_service.py:44", "link": "#L44"},
            {"id": "e-cmdb", "source": "servicenow-cmdb", "summary": "CI owner = Payments Platform Support", "link": "#cmdb"}
          ],
          "contradictingEvidence": [],
          "missingInformation": ["Affected user id"],
          "recommendedNextAction": "Confirm discount-before-tax vs after-tax in payment_service.reconcile().",
          "confidenceOverall": "MEDIUM",
          "advisory": true
        }
        """;
}
