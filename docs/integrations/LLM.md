# LLM provider — getting credentials (live ADK agent mode)

Used by: the live **Google ADK** agent engine (`-Padk` build,
`--triage.engine=adk`) — see `src/main/adk/`.
Fills `secrets.properties` keys `triage.integrations.llm.{base-url,api-key,model}`.

This is only needed for the **optional live agent mode**. The default
`deterministic` engine (used for the offline demo) needs none of this.

The ADK engine here talks to any **OpenAI-compatible** chat completions endpoint via
langchain4j's `langchain4j-open-ai` module — so any of the following work:

## Option A: OpenAI directly

1. Go to https://platform.openai.com/api-keys (sign in / create an account).
2. **Create new secret key** → name it (e.g. `triagemate-demo`) → copy it immediately.
3. Fill `secrets.properties`:
   ```properties
   triage.integrations.llm.base-url=https://api.openai.com/v1
   triage.integrations.llm.api-key=<key from step 2>
   triage.integrations.llm.model=gpt-4o-mini
   ```

## Option B: An internal/company LLM gateway

Many orgs proxy LLM calls through an internal gateway for cost control and audit
logging. Ask your platform/infra team for:

- The gateway's OpenAI-compatible base URL (→ `triage.integrations.llm.base-url`)
- An API key for it (→ `triage.integrations.llm.api-key`)
- Which model name to request (→ `triage.integrations.llm.model`)

## Option C: Any other OpenAI-compatible provider

Azure OpenAI, OpenRouter, a self-hosted vLLM/Ollama server, etc. all work as long as
they expose a `/chat/completions`-compatible endpoint — set the `base-url` to that
provider's base URL and `api-key`/`model` accordingly per their docs.

## Confirm access

```bash
curl "https://api.openai.com/v1/chat/completions" \
  -H "Authorization: Bearer <your-api-key>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"say hi"}]}'
```

A `200` with a completion response confirms it's working.

## Fill `secrets.properties` and run

```bash
cp secrets.properties.example secrets.properties
# fill in triage.integrations.llm.{base-url,api-key,model}
mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk
```

> The ADK path reads these three settings from `secrets.properties` (Spring dotted
> keys). Env vars `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` and `-D` system
> properties still work too and take precedence.

## Notes

- Use a **low-cost model** (`gpt-4o-mini` or similar) for demo/dev runs — the agent
  loop makes several tool-calling round trips per triage.
- Set a spend cap / budget alert on the API key if using a personal OpenAI account —
  agent loops can retry and burn tokens faster than a single chat prompt.
