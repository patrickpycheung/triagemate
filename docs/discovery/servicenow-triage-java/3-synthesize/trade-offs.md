# Trade-offs accepted

| We accept | In exchange for | Mitigation |
|---|---|---|
| ADK-Java's RxJava surface (`Flowable`/`Single`) new to a Java team | The tool-call loop, bounds, callbacks, sessions, event stream for free | Hide reactive types inside `AdkAgentConfig`; expose a blocking `run()` to J1 |
| Building connector code + credentials ourselves | Full control, model-neutral, offline-demoable, no Rovo/Forge dependency | Mock ⇄ real behind one interface; reuse `auspost-mcp` clients |
| Deterministic phase ordering (less "free-roam agent" wow) | Reliability + debuggability + safety | The wow is the **log↔code citation** (RC3/J6), not autonomy |
| No RAG / vector DB | Skips permissions/staleness/re-index/citation problems | Live keyword/API search + cite sources; tiny curated index only if needed |
| Live model round-trip unproven until build day (JS-1b) | Not blocking design; escape hatch exists | Two backend routes + Spring-AI fallback; mock agent path keeps demo alive |
| One demo app only | A polished, credible, offline demo | Explicitly a non-goal to cover the enterprise |
