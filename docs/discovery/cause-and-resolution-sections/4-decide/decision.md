# Decisions

Both were routed to the operator and both were re-classified as **ADM-2** on testing the
parking premise, per the "re-classify inherited items; never relay a parked class" rule.
Decided by the agent, recorded here with the watch-item that would reverse each.

---

## D1 — Take **both** J26 and J27 to CDS, with J27 blocking

**Classification**: ADM-2. A design recommendation out of a discovery process is
reversible and is itself the deliverable — the operator reads it and can redirect. Not a
charter, budget, or authority question.

**Decision**: both concepts proceed; **J27 blocks J26**; **FND-87 is a hard prerequisite**
before J26 lands on the ADK path.

**Why**: J26 is a *rendering of* `findSimilarIncidents`. Shipping it on the current signal
produces the single worst available outcome — correct-looking attribution pointing at an
unrelated ticket, which converts attribution (J26's primary safety mechanism) into
borrowed credibility for noise. J27 is also cheap and independently worth doing, so
sequencing it first costs almost nothing.

FND-87 is separately non-negotiable: without it, J26's cause text makes run 1's hypothesis
into run 3's stated cause through a circular chain that `evidenceRefs` validation cannot
detect, because every link is a genuine, correctly-cited artifact.

**Watch-item**: if J27 turns out to be more than a few hours (e.g. real similarity scoring
needs a ServiceNow feature we can't reach from the demo environment), re-cut it — ship the
stopword fix and *suppress the similarity display entirely* rather than blocking J26 on a
proper scorer.

---

## D2 — **Historical framing now, predictive later** (C3)

**Classification**: ADM-2, not ADM-4. The parking premise was "this is a new public
claim". Tested and **false at this stage**: nothing is deployed, the default profile is
all-mock, the default write target is `work_notes` (internal ITSM) rather than
customer-facing `comments` — which needs an explicit `--triage.servicenow.write-field`
flag — and a wording choice in a design document asserts nothing to anyone. The claim
becomes public at deploy, which is a separate and separately-gated act.

**Decision**: ship the **historical** framing for the hackathon — *"Why this may be
happening"* and *"How similar incidents **were** resolved"*, quoted and attributed —
and treat the **predictive** framing as the destination to revisit once J27 lands.

**Why historical now**: 📚 LLM root-cause correctness is **2.40–2.88 / 5** while
readability is **3.5–4.6** (Ahmed et al., ICSE 2023, 44,340 incidents graded by the
engineers who fixed them). A predictive claim would read convincing and usually be wrong —
the worst possible combination on an incident record. All seven explorations independently
concluded the app has no causal substrate to license the stronger claim.

**Why not historical permanently**: the request was explicitly predictive. Historical
*partially* fulfils it — it is silent on a novel incident with no precedent, which is
exactly the case where an engineer most needs help. Committing to historical forever would
be quietly narrowing the operator's stated scope, which is not the agent's call to make.
Staging preserves the actual ask as the destination.

**Watch-item — the two facts that would license predictive immediately**: (1) J27 yields a
genuine similarity signal, and (2) `open-questions.md` Q1 comes back showing real
`close_notes` are substantively populated. Those two together are precisely what would
convert the analogical transfer from speculation into evidence. If both land, revisit
without waiting to be asked.

---

## Not decided here — genuinely the operator's

Nothing in this DDS reached ADM-4. The three ❓ Unknowns in
`../2-diverge/verification-similar-incidents/open-questions.md` need the corp laptop, but
they are an **information gap, not a decision** — they need someone to run a read-only
query, not to exercise judgement.

Deploying any of this to a real incident queue **would** be ADM-4. That is not in scope
here and is not implied by building it.
