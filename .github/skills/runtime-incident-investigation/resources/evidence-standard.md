# Evidence Standard

Evidence records use type-prefixed, monotonically increasing IDs:

- `CODE-001` for local source code.
- `LOG-001` for remote runtime logs.
- `DB-001` for database query results.
- `CTX-001` for reporter-provided facts, deployment metadata, or other bounded context.

Do not reuse or renumber an ID after it has been cited.

## Required fields

Every evidence item must include:

| Field | Requirement |
|---|---|
| ID | Stable ID in the correct namespace. |
| Source type | `code`, `log`, `database`, or `context`. |
| Exact source location | Repository path and lines, SSH target and log path, database alias and object, or named context source. |
| Environment | Explicit environment or `not applicable` for code. |
| Timestamp or revision | Timestamp with time zone for runtime sources; code revision when known. |
| Observed fact | Minimal statement directly supported by the source. |
| Interpretation | Reasoned meaning, labeled as interpretation. |
| Hypothesis effect | Hypothesis IDs supported or refuted. |
| Confidence | `high`, `medium`, or `low`, with a brief reason. |
| Limitations | Access, sampling, clock, retention, revision, replication, or query limitations. |
| Does not prove | Explicit boundary on the claim. |
| Redactions | Redacted fields or `none`. |

Keep observations free of causal language unless the source itself establishes causation. Code describes possible behavior; logs describe recorded execution; database queries describe bounded state.

## Causal chain

The final report must derive:

```text
Trigger
→ Entry point
→ Code path
→ Runtime behavior
→ Data or state condition
→ Failed condition
→ User-visible result
```

Cite at least one evidence ID at every link. If a link is inferred, label it and explain the inference. A `CONFIRMED` conclusion requires direct support for every material link and elimination of material alternatives.

## Absence and negative evidence

An empty query result proves only that the bounded query returned no rows at the query time. Missing log matches prove only that no match was found in the searched targets, paths, patterns, and time window. Neither proves that an event or record never existed.

## Contradictions

Preserve conflicting evidence. Check time zones, clock skew, code revision, deployment version, replicas, cache state, retries, asynchronous ordering, and source completeness. If conflict remains unresolved, lower confidence and identify the evidence required to resolve it.
