# J3 — Connector Tools (gateways + FunctionTool adapters)

**State**: 🟢 Built · **Complexity**: Moderate · **Depends on**: J4

## Essence
Each enterprise system is a Spring `@Service` behind a narrow interface, with a
**mock** and a **real** implementation, adapted to an ADK `FunctionTool`. Plain
Java tools — **no MCP, no Rovo skills** (deferred). Reuses `auspost-mcp`'s GitLab +
Confluence integration code.

## Gateway interfaces (typed to J4 model; FND-21/29 — corrected to match the actual
## interfaces, which grew past this sketch as J9/J10 were added)
```java
interface ServiceNowGateway {                         // J5
  IncidentContext getIncident(String number);
  List<ResolvedIncident> findSimilarIncidents(IncidentContext ctx);
  Optional<ServiceOwnership> findOwnership(String applicationName);
  void addWorkNote(String number, String note);        // advisory, AUTOMATIC (no confirm gate)
  List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit);  // J10's K1 trigger
}
interface ConfluenceGateway {                          // J6
  List<KnowledgeDoc> search(String query);   // mock: plain keyword match. real: genuine
                                              // CQL (`text ~ "query"`) — they differ (below)
  List<Contact> contributors(KnowledgeDoc doc);  // J9 — default no-op; only for a page already cited
}
interface SumoGateway   { List<LogEvidence> search(LogSearchRequest r); }   // J6, bounded
interface GitLabGateway {                              // J6
  List<CodeSearchResult> searchCode(String project, String term);
  List<Contact> recentCommitters(String project, String filePath);  // J9
}
```

**Correction (re-verification, 2026-07-30): mock and real Confluence search are NOT the
same query language.** The earlier FND-29 fix over-corrected — it said neither
implementation does CQL. `MockConfluenceGateway` does plain keyword/substring matching,
but `RealConfluenceGateway.search` genuinely builds a CQL expression
(`text ~ "<query>"`) and sends it via the Confluence content-search API's `cql=`
parameter — that IS Confluence Query Language, just a simple one-clause form of it.

## Mock ⇄ Real
- `Mock*Gateway` (default) — serves the J7 ground-truth dataset (reuses S3′ Sumo
  fixture + `seed-repo`). Lets the whole demo run offline and lets development
  proceed before API approvals land.
- `Real*Gateway` — ServiceNow REST Table API; Confluence **CQL** search (`text ~
  "query"`) + page REST; Sumo Search-Job API (access id/key + regional endpoint);
  GitLab search/file REST (reuse `auspost-mcp` gitlab4j + confluence clients).
- **Selection**: per-connector `@ConditionalOnProperty(name=
  "triage.connectors.<system>", havingValue="mock"|"real")` — **not** Spring
  `@Profile` (FND-10; there is no `mock` profile). Mix freely per connector.

## FunctionTool adapters
`TriageMateTools` wraps gateway calls with `@Schema` descriptions the LLM sees, and
applies per-call limits (FND-20: log search window and result count are now bounded
here, not trusted from the model). All eight tools are registered on the one agent
and gated by a single **global** allowlist (J2/J8) — there is no per-step
allowlisting (see the FND-13 correction on J2).

## Verification
- **Not per-gateway** (re-verification correction, 2026-07-30): there is no
  `*GatewayTest` per mock gateway. Coverage is indirect, via
  `DeterministicDiagnosisEngineTest#diagnosesTheSeededIncidentEndToEnd`, which
  exercises all four mock gateways together in one run. A gateway-level regression
  could slip through if the deterministic engine's happy path doesn't touch it.
- Each `*Tool` exposes a correct JSON schema and clamps oversized results
  (`TriageMateToolsSearchLogsTest`, FND-20).
- Swapping `mock`→`real` for one gateway (JS-2) changes no orchestrator code.

## Open / risks
- GitLab intranet-only reachability → deployment-placement problem, not code; mock
  covers the demo (snapshot of one repo). Sumo regional endpoint must be correct.
