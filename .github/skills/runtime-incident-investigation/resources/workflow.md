# Investigation Workflow

Use this sequence for every incident. Record decisions and evidence gaps as work proceeds.

## 1. Normalize the incident input

Map the report to `incident-request.template.json`. Preserve the reporter's words separately from normalized fields. Normalize timestamps to an explicit time zone without discarding the original value. Treat omitted fields as unknown, not empty facts.

Ask one concise question containing all missing critical facts only if no safe evidence search can begin. An environment plus one useful discriminator, such as an API path, service hint, trace ID, business ID, error, or bounded time window, is normally sufficient to start.

## 2. Read and validate the service catalog

Load `assets/service-catalog.local.json`. Run `scripts/validate_service_catalog.py` before using any catalog value. Do not connect to an alias or path from an invalid catalog. Never infer a credential from catalog data.

## 3. Identify candidate services

Run `scripts/resolve_service.py` with every available signal. Review ranked candidates, scores, explanations, and matched signals. Show the selected service and reason before evidence collection. Do not silently choose an ambiguous or low-confidence candidate.

## 4. Build hypotheses

Create stable hypothesis IDs such as `HYP-001`. Each hypothesis must be falsifiable and identify the proposed trigger, component, behavior, and user-visible consequence. Include plausible alternatives, including client input, routing, downstream, data-state, and environmental explanations when relevant.

## 5. Select the minimum evidence lanes

Apply `routing-and-escalation.md`. Choose code, logs, database, or the smallest combination that can test the active hypotheses. State why every selected lane is necessary and why omitted lanes are not yet required.

## 6. Collect evidence independently

Delegate narrow scopes to evidence agents. Include the selected service, environment, bounded time window, identifiers, hypotheses, allowed source locations, and required output contract. Run independent agents in parallel when possible. Evidence agents must not decide the final root cause or invoke nested agents.

## 7. Evaluate sufficiency and escalate

Check each item against `evidence-standard.md`. Expand only when a material causal link is missing, a relevant alternative remains untested, evidence conflicts, or a cited downstream service becomes a candidate. Keep every expansion bounded and explain it.

## 8. Run the synthesizer

Provide normalized facts, service resolution, selected scope, hypotheses, evidence ledger, explicit gaps, and contradictions. Do not provide a preferred conclusion.

## 9. Produce the final report

Use the sixteen-section report structure in `synthesis-and-response.md`. Cite evidence IDs for every material factual claim. Include a causal chain, repair plan, rollback and validation strategy, prevention, and a ready-to-send reply.

## 10. Apply the completion gate

Never claim completion when critical evidence is missing. Use:

- `CONFIRMED` only for a fully supported causal chain with material alternatives eliminated.
- `PROBABLE` when one important link remains inferred.
- `INCONCLUSIVE` when the available evidence cannot support a defensible root cause.

List the evidence needed to raise confidence, including its source, scope, and purpose.
