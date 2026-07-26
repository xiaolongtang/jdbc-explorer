# Synthesis and Response

The synthesizer must not collect new evidence. It must cross-check the supplied ledger, preserve exact IDs, reject unsupported claims, expose contradictions, and separate facts, interpretations, and assumptions.

## Conclusion status

- `CONFIRMED`: Direct evidence supports the full material causal chain and material alternatives have been eliminated.
- `PROBABLE`: Evidence strongly supports one explanation, but at least one important link remains inferred or one material alternative cannot be fully tested.
- `INCONCLUSIVE`: Available evidence cannot support a defensible root cause.

Never upgrade status to make the result sound complete.

## Required report structure

1. **Executive Conclusion** — Status, concise cause, impact, and confidence boundary.
2. **Incident Facts** — Normalized facts and `CTX-NNN` sources.
3. **Service Identification** — Selected service, score, signals, and ambiguity.
4. **Investigation Scope and Selected Evidence Lanes** — Included and omitted lanes with reasons.
5. **Evidence Ledger** — Complete records with observations separated from interpretations.
6. **Reconstructed Timeline** — Time-zone-qualified events with evidence IDs.
7. **Causal Chain** — Trigger through user-visible result, with evidence at every link.
8. **Alternative Hypotheses and Elimination** — Supported, refuted, and untested alternatives.
9. **Root Cause** — Defensible technical cause and classification.
10. **Immediate Mitigation** — Reversible short-term risk reduction; do not execute it.
11. **Permanent Fix** — Code, data, configuration, or operational change direction; do not implement it.
12. **Validation and Regression Tests** — Focused tests and environment validation.
13. **Rollback Plan** — Trigger, steps, and verification for reverting an approved fix.
14. **Monitoring and Prevention** — Signals, alerts, dashboards, and preventive controls.
15. **Ready-to-Send Reply** — Plain-language response for QA, BA, or the reporter.
16. **Remaining Unknowns** — Missing evidence and its effect on confidence.

## Repair plan standard

Keep these parts distinct:

- Immediate mitigation.
- Permanent code, data, configuration, or operational fix.
- Regression tests.
- Environment validation.
- Rollback.
- Monitoring.
- Prevention.

Tie each proposed action to a causal-chain link or remaining risk. Label any data or environment change as requiring separate approval.

## Stakeholder reply

Explain what happened, user impact, confirmed or probable cause, current status, immediate action, permanent fix, validation, and any recipient action. Avoid excessive implementation detail. Preserve the conclusion status and do not state a probable explanation as confirmed.
