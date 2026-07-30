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
  List<KnowledgeDoc> search(String query);              // keyword match — NOT CQL, despite the name
  List<Contact> contributors(KnowledgeDoc doc);  // J9 — default no-op; only for a page already cited
}
interface SumoGateway   { List<LogEvidence> search(LogSearchRequest r); }   // J6, bounded
interface GitLabGateway {                              // J6
  List<CodeSearchResult> searchCode(String project, String term);
  List<Committer> recentCommitters(String project, String filePath);  // J9
}
```

## Mock ⇄ Real
- `Mock*Gateway` (default) — serves the J7 ground-truth dataset (reuses S3′ Sumo
  fixture + `seed-repo`). Lets the whole demo run offline and lets development
  proceed before API approvals land.
- `Real*Gateway` — ServiceNow REST Table API; Confluence keyword search + page REST;
  Sumo Search-Job API (access id/key + regional endpoint); GitLab search/file REST
  (reuse `auspost-mcp` gitlab4j + confluence clients).
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
- Every gateway has a passing mock unit test returning dataset fixtures.
- Each `*Tool` exposes a correct JSON schema and clamps oversized results.
- Swapping `mock`→`real` for one gateway (JS-2) changes no orchestrator code.

## Open / risks
- GitLab intranet-only reachability → deployment-placement problem, not code; mock
  covers the demo (snapshot of one repo). Sumo regional endpoint must be correct.
