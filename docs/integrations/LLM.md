# LLM provider — getting credentials (live ADK agent mode)

Used by: the live **Google ADK** agent engine (`-Padk` build,
`--triage.engine=adk`) — see `src/main/adk/`.
Fills `.env` vars `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`.

This is only needed for the **optional live agent mode**. The default
`deterministic` engine (used for the offline demo) needs none of this.

The ADK engine here talks to any **OpenAI-compatible** chat completions endpoint via
langchain4j's `langchain4j-open-ai` module — so any of the following work:

## Option A: OpenAI directly

1. Go to https://platform.openai.com/api-keys (sign in / create an account).
2. **Create new secret key** → name it (e.g. `triagemate-demo`) → copy it immediately.
3. Fill `.env`:
   ```
   LLM_BASE_URL=https://api.openai.com/v1
   LLM_API_KEY=<key from step 2>
   LLM_MODEL=gpt-4o-mini
   ```

## Option B: An internal/company LLM gateway

Many orgs proxy LLM calls through an internal gateway for cost control and audit
logging. Ask your platform/infra team for:

- The gateway's OpenAI-compatible base URL (→ `LLM_BASE_URL`)
- An API key for it (→ `LLM_API_KEY`)
- Which model name to request (→ `LLM_MODEL`)

## Option C: Any other OpenAI-compatible provider

Azure OpenAI, OpenRouter, a self-hosted vLLM/Ollama server, etc. all work as long as
they expose a `/chat/completions`-compatible endpoint — set `LLM_BASE_URL` to that
provider's base URL and `LLM_API_KEY`/`LLM_MODEL` accordingly per their docs.

## Confirm access

```bash
curl "$LLM_BASE_URL/chat/completions" \
  -H "Authorization: Bearer $LLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$LLM_MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}"
```

A `200` with a completion response confirms it's working.

## Fill `.env` and run

```bash
cp .env.example .env   # fill in LLM_BASE_URL / LLM_API_KEY / LLM_MODEL
export $(grep -v '^#' .env | xargs)
mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk
```

## Notes

- Use a **low-cost model** (`gpt-4o-mini` or similar) for demo/dev runs — the agent
  loop makes several tool-calling round trips per triage.
- Set a spend cap / budget alert on the API key if using a personal OpenAI account —
  agent loops can retry and burn tokens faster than a single chat prompt.
