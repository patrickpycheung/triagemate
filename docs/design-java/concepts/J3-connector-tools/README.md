# J3 — Connector Tools (gateways + FunctionTool adapters)

**State**: 🟡 Drafted · **Complexity**: Moderate · **Depends on**: J4

## Essence
Each enterprise system is a Spring `@Service` behind a narrow interface, with a
**mock** and a **real** implementation, adapted to an ADK `FunctionTool`. Plain
Java tools — **no MCP, no Rovo skills** (deferred). Reuses `auspost-mcp`'s GitLab +
Confluence integration code.

## Gateway interfaces (from the analysis, typed to J4 model)
```java
interface ServiceNowGateway {                         // J5
  IncidentContext getIncident(String number);
  List<ResolvedIncident> findSimilarIncidents(IncidentContext ctx);
  Optional<ServiceOwnership> findOwnership(String applicationName);
  void addWorkNote(String number, String note);       // advisory, confirmed
}
interface ConfluenceGateway { List<KnowledgeDoc> search(String cql); }        // J6
interface SumoGateway       { List<LogEvidence> search(LogSearchRequest r); } // J6, bounded
interface GitLabGateway     { List<CodeSearchResult> searchCode(String project, String term); } // J6
```

## Mock ⇄ Real
- `Mock*Gateway` (`@Profile("mock")`, default) — serves the J7 ground-truth dataset
  (reuses S3′ Sumo fixture + `seed-repo`). Lets the whole demo run offline and lets
  development proceed before API approvals land.
- `Real*Gateway` (`@Profile("real")`) — ServiceNow REST Table API; Confluence CQL +
  page REST; Sumo Search-Job API (access id/key + regional endpoint); GitLab
  search/file REST (reuse `auspost-mcp` gitlab4j + confluence clients).

## FunctionTool adapters
Thin `agent/tools/*Tool` classes wrap gateway calls with `@Schema` descriptions the
LLM sees, and apply per-call limits. Each adapter is what the app allowlists per
step (J2), so a tool exists ⇏ the model may call it anywhere.

## Verification
- Every gateway has a passing mock unit test returning dataset fixtures.
- Each `*Tool` exposes a correct JSON schema and clamps oversized results.
- Swapping `mock`→`real` for one gateway (JS-2) changes no orchestrator code.

## Open / risks
- GitLab intranet-only reachability → deployment-placement problem, not code; mock
  covers the demo (snapshot of one repo). Sumo regional endpoint must be correct.
