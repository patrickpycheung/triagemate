# Found issues

**Backlog: empty.** ✅ Drained 2026-07-30 by `/found-issues-resolve`.

Queue of findings that need a decision or a fix and are not yet tracked elsewhere.
Resolved entries move to [`docs/audit/found-issues-archive.md`](docs/audit/found-issues-archive.md)
with two added lines: a **Resolution** (`fixed:<sha>` or `promoted:<card>`) and an
**Escape** (which *process layer* should have caught it — the input a future retrospective
clusters on).

Format: `## FND-<n> — <one-line title> · **HIGH|MEDIUM|LOW**`, then **Where** / **What** /
**Why it matters**. Add new entries at the bottom.

## When to log here vs. fix directly

- **Log it** when resolving it means a design decision, a behaviour change, or a data /
  API contract change — anything where picking the fix is itself the hard part.
- **Fix it directly** (no entry) when it is a genuinely small correction with an easy undo
  and no money / legal / data / governance / deploy surface. Don't force ceremony onto
  trivia.

---

_(no open entries)_

## Previously drained

The first eight entries (FND-1…FND-8) came out of `/doc-test dds` on 2026-07-29 plus the
work that followed, and were all resolved on 2026-07-30 — see the archive. Two were worth
the trip on their own:

- **FND-1** — the trigger the DDS specified would have re-diagnosed every incident it
  commented on, forever, because the app's own work notes bumped the cursor it polled on.
- **FND-8** — the FND-7 fallback was correct but silent, so a run that never called the
  model looked exactly like a successful one. It cost a spike cycle before anyone noticed.
