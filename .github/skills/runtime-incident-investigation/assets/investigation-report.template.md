# Runtime Incident Investigation Report

## 1. Executive Conclusion

State `CONFIRMED`, `PROBABLE`, or `INCONCLUSIVE`, the concise cause, impact, and confidence boundary.

## 2. Incident Facts

List normalized reporter facts and cite `CTX-NNN` records. Mark unknown values.

## 3. Service Identification

Show ranked candidates, selected service, score, matched signals, and any ambiguity.

## 4. Investigation Scope and Selected Evidence Lanes

Explain selected and omitted lanes, services, environments, time windows, and expansion decisions.

## 5. Evidence Ledger

Include complete `CODE-NNN`, `LOG-NNN`, `DB-NNN`, and `CTX-NNN` records. Keep observations separate from interpretations.

## 6. Reconstructed Timeline

Order evidence-backed events with explicit time zones and clock limitations.

## 7. Causal Chain

Derive and cite every link:

```text
Trigger
→ Entry point
→ Code path
→ Runtime behavior
→ Data or state condition
→ Failed condition
→ User-visible result
```

## 8. Alternative Hypotheses and Elimination

List each hypothesis, evidence supporting or refuting it, and unresolved tests.

## 9. Root Cause

State the defensible cause, contributing conditions, and conclusion classification without overstating evidence.

## 10. Immediate Mitigation

Describe a reversible short-term action. Mark all data and environment actions as requiring separate approval.

## 11. Permanent Fix

Describe the approved-fix direction for code, data, configuration, or operations. Do not implement it during investigation.

## 12. Validation and Regression Tests

Define focused unit, integration, regression, and environment checks tied to the causal chain.

## 13. Rollback Plan

Define rollback triggers, steps, ownership, and post-rollback verification.

## 14. Monitoring and Prevention

Define logs, metrics, traces, alerts, dashboards, and preventive controls.

## 15. Ready-to-Send Reply

Provide an accurate plain-language response for QA, BA, or the reporter.

## 16. Remaining Unknowns

List missing evidence, blocked operations, contradictions, and the effect on confidence.
