# Integration credentials

How to obtain the keys/tokens for each real connector. All of these fill in
`secrets.properties` (copy `secrets.properties.example` at the repo root) — a standard
Java properties file that Spring Boot auto-imports at startup. See the main
[README](../../README.md) for how the app loads them.

- [ServiceNow](SERVICENOW.md) — required for the live demo (`-Dtriage.connectors.servicenow=real`)
- [Confluence](CONFLUENCE.md)
- [Sumo Logic](SUMOLOGIC.md)
- [GitLab](GITLAB.md)
- [LLM provider](LLM.md) — for the optional live ADK agent mode
