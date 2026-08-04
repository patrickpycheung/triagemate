# Research Report: Live Thinking & Agent Trace UIs

## Key Findings
*   **The "Labor Illusion" is Real:** Showing the steps of a complex operation increases perceived value and trust. Delivering instant results for difficult tasks often breeds suspicion.
*   **Transport is Standardized on SSE:** Both OpenAI and Anthropic rely on Server-Sent Events (SSE), streaming tool-calls as incremental JSON fragments that must be buffered and parsed client-side.
*   **Trace UIs Append and Collapse:** Frontier products (ChatGPT o1, Claude, Perplexity) generally append results below a step and auto-collapse the step once completed, rather than replacing the label in-place. This preserves the "trace" history.
*   **Artificial Pacing is a Gray Area:** Deliberately slowing down a 19ms operation creates a conflict between UX principles (showing "work" for trust) and engineering ethics (never lying to the user).
*   **Logos Require Nuance:** While `simple-icons` provides SVGs under a CC0 license, trademark laws still govern their display. For an internal hackathon, using them is defensible, but the safest public approach relies on generic glyphs and text for nominative use.

---

## Deep Analysis

### (1) Presentation Vocabulary
How frontier products present live agent steps:

*   **ChatGPT (o1 / Deep Research):** 
    *   *Visuals:* Uses an expandable accordion labeled "Thinking" or "Thought" with a pulsing/breathing animation while active.
    *   *Timing:* Explicitly shows elapsed time upon completion (e.g., "Thought for 14 seconds").
    *   *Structure:* The trace expands inline. Final text is appended below the thought block. The block auto-collapses by default to reduce visual pollution.
*   **Claude (Tool Use / Claude Code):**
    *   *Visuals:* Uses spinners (e.g., "Shimmying..." or "Running tool"). 
    *   *Structure:* Distinct reasoning blocks are generated. Once a tool finishes, the result is appended, and the thinking steps often collapse.
*   **Perplexity:**
    *   *Visuals:* Shows a stacked list of discrete action steps ("Researching", "Understanding prompt") with spinners.
    *   *Structure:* Steps do not typically get replaced in-place; they stack, forming a historical trace of the work, followed by the final synthesized answer.
*   **GitHub Copilot Workspace:**
    *   *Visuals:* Emphasizes a "steerable plan" UI. Shows a checklist of files to read and commands to run.
    *   *Structure:* Checkmarks appear as steps complete. The UI is highly transparent, exposing the terminal commands and diffs inline.
*   **Atlassian Rovo:**
    *   *Visuals:* Uses a "Debug View" accessible via a button, showing scenarios triggered and skills invoked.
    *   *Logos:* Tends to use standard internal glyphs for generic skills, but may use specific tool icons when hitting external integrations.

**In-place Replacement vs. Appending:** Products overwhelmingly **append** results or embed them within an expandable container. Replacing the in-progress label in-place is rare because it destroys the timeline (the "trace"), confusing users who want to audit what the agent actually did.

### (2) Transport (SSE vs. WebSocket)
Frontier models use **Server-Sent Events (SSE)** for streaming, not WebSockets. The streaming shapes for tool calls are well-documented:

*   **OpenAI:**
    *   Streams via `data: { ... }` chunks.
    *   Tool calls are located in `delta.tool_calls`.
    *   The first chunk contains the `index`, `id`, and function `name`. Subsequent chunks stream fragments of the JSON `arguments`.
    *   The client must buffer these fragments by `index` and parse the final JSON when `finish_reason: "tool_calls"` is received.
*   **Anthropic (Claude):**
    *   Uses highly structured, typed SSE events.
    *   Starts with `content_block_start` (where `type: "tool_use"`).
    *   Streams data via `content_block_delta` containing `partial_json`.
    *   Ends with `content_block_stop`, which is the trigger for the client to parse the accumulated JSON buffer.
    *   Supports "fine-grained tool streaming" for character-by-character updates.

### (3) Perceived-Wait UX Research
The crux of the pacing debate revolves around **Jakob Nielsen's 100ms threshold** (actions under 100ms feel instantaneous) and the **"Labor Illusion"** (Buell & Norton, 2011).

*   **The Labor Illusion:** Users anthropomorphize AI. If they ask a complex question and get a 19ms response, they assume the system didn't "think" hard enough, degrading trust. Demonstrating the work (e.g., Kayak's flight search) increases perceived value.
*   **Animation Dynamics:** Research shows that moderate-speed, fast-to-slow animations provide the most reassurance. Stalling at 99% destroys trust.

**The 19-millisecond Dilemma:** Should you pace a 19ms operation for legibility if your core principle is "never show users something untrue"?
*   *Argument FOR Pacing:* The user's cognitive processing speed is the bottleneck. Flashing a UI element for 19ms creates a subliminal flicker that feels like a glitch. Pacing it to ~300ms translates the *machine's* reality into a frequency the *human* can actually perceive and trust. It's translation, not deception.
*   *Argument AGAINST Pacing:* It is fundamentally a dark pattern. Fake progress bars degrade long-term trust once discovered. If the system is fast, celebrate the speed rather than crippling it to coddle legacy human expectations.

### (4) Logo / Trademark Practicality
*   **The `simple-icons` License:** The repository itself is licensed under **CC0 1.0 (Public Domain)**. This means you can freely copy and modify the SVG files.
*   **The Trademark Catch:** The brands represented by those SVGs (ServiceNow, Atlassian, GitLab) are aggressively protected by trademark law. CC0 does not override trademark rights.
*   **Nominative Fair Use:** You can use a brand name to describe an integration (e.g., "Export to Jira") if it's necessary, minimal, and implies no endorsement.
*   **Brand Guidelines:** 
    *   *ServiceNow:* Extremely strict. Explicitly forbids using their logo in third-party UIs without a partnership.
    *   *Atlassian/GitLab:* Require that your UI looks like *your* brand. Logos should not be used as generic action buttons.
*   **Hackathon Reality:** For an internal-only, offline hackathon demo shown on a projector, embedding the SVGs is completely defensible. There is zero commercial harm or public confusion. 

---

## Trade-offs and Tensions
1.  **Transparency vs. Visual Clutter:** Showing every step builds trust but overwhelms the UI. *Resolution:* Auto-collapsing accordions (like ChatGPT o1).
2.  **Speed vs. Trust (The 19ms Tension):** Machine speed outpaces human perception, making fast systems look unreliable. *Resolution:* Use micro-animations (e.g., a 200ms pulse) that ensure legibility without forcing a multi-second fake wait.
3.  **Brand Recognition vs. Legal Risk:** Logos are instantly recognizable but legally precarious. *Resolution:* Use text labels with generic category icons (e.g., a generic ticket for ServiceNow).

---

## Recommendations
1.  **UI Design:** Implement a stacked, accordion-style trace. As steps initiate, append them to a list with a generic pulsing icon (not a full spinner). When the step completes, replace the pulse with a checkmark and auto-collapse the content. Do *not* replace the text in-place.
2.  **Handling the 19ms Operation:** Do not enforce a fake multi-second delay. Instead, enforce a **minimum visual display time of 250ms** for any discrete UI step. This is long enough to be perceived by the human eye without feeling like an artificial throttle. It satisfies both the "no fake data" principle and human perceptual needs.
3.  **Transport Implementation:** Since this is a Spring Boot hackathon demo without a framework, use vanilla JavaScript `EventSource` to consume an SSE endpoint from Spring WebFlux. Buffer the JSON fragments locally in JS based on a step ID.
4.  **Logos for the Demo:** Use the CC0 `simple-icons` SVGs for the projector demo to maximize the "wow" factor. However, build a CSS fallback class (`.generic-integration-icon`) using a plain text abbreviation just in case you decide to record and publish the demo publicly later.

---

## Confidence Levels
*   **High Confidence:** Transport methods (SSE is definitively the standard); UI trace patterns (append & collapse is the dominant paradigm); Nielsen's 100ms threshold.
*   **Medium Confidence:** How companies internally balance the "Labor Illusion" vs. engineering ethics (this is heavily debated in UX circles).
*   **Low Confidence:** Exact millisecond timings used by OpenAI for their pulsing animations (these are proprietary and subject to silent A/B testing).
