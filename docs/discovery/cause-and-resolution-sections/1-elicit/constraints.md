# Constraints

## Hard — a proposal violating these is disqualified

| # | Constraint | Source |
|---|-----------|--------|
| H1 | **Advisory only.** The app comments; it never reassigns, closes, re-prioritises, or executes. A resolution section suggests; it never acts. | README, J4 (`advisory` always `true`) |
| H2 | **Every conclusion ties to `evidenceRefs`.** Enforced by `DiagnosisReportValidator`. | J4, J13 |
| H3 | **Both engines must produce it.** Deterministic (no LLM, selects+templates only) and ADK. | `docs/design-java/STATUS.md` |
| H4 | **No new connectors.** Hackathon scope; four connectors already exist and are enough. | RAPID rigor |
| H5 | **Offline demo path must stay green.** `mvn test` and `./run-deterministic.sh` work with no network. | README |
| H6 | **`suggestedContacts` stays UI-only.** Any new field must consciously declare which surface it targets. | J9 |
| H7 | **Abstention must be legal.** The contract must permit "no cause determined" without being invalid — otherwise the schema itself forces fabrication. | Derived from H2; see `problem-statement.md` §"different epistemic class" |

## Soft — strong preferences, tradeable with justification

| # | Constraint | Note |
|---|-----------|------|
| S1 | Minimal schema delta | Each new field must earn its place |
| S2 | Sound like the existing note | "What appears to have happened:" sets the register |
| S3 | Cheap to build | Hackathon timeline; hours not days |
| S4 | Demo-legible in one glance | The why+how is the emotional payoff of the pitch |
| S5 | Consistent uncertainty display with `candidateSystems` | UI already shows confidence % |

## Non-constraints — explicitly NOT limits

- **Not deployed.** Local PoC for a presentation. Default profile is all-mock; nothing
  external is touched unless a connector is explicitly flipped to `real`.
- **No live production blast radius today.** Real ServiceNow write-back requires
  deliberate opt-in with filled-in `secrets.properties`.

This bounds the *present* risk. It does not bound the *designed* risk: the exploration
should still reason about what happens if this is pointed at a real incident queue,
because that is the product's stated intent.
