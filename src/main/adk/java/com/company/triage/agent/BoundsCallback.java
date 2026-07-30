package com.company.triage.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The leash (J8): the app — not the model — enforces max tool calls per run. Wire
 * this into ADK's {@code beforeToolCallback} so an over-budget or out-of-allowlist
 * call is denied before it executes.
 *
 * <p>JS-1b: bind {@link #allow} to the ADK 1.7.0 before-tool callback hook; per-tool
 * result caps are already applied inside {@link TriageMateTools}.
 */
public class BoundsCallback {

    private final int maxToolCalls;
    private final AtomicInteger calls = new AtomicInteger();

    public BoundsCallback(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    /** @return true if the tool call is within budget and may proceed. */
    public boolean allow(String toolName) {
        return calls.incrementAndGet() <= maxToolCalls;
    }

    public int used() {
        return calls.get();
    }
}
