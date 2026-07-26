# Code Investigation

Collect source facts without editing code or claiming that possible behavior occurred at runtime.

## Scope

Use the selected service's `workspaceFolder` or `localRepositoryPath` from the validated catalog. Record the active branch, revision, and any known difference from the deployed revision. Do not search unrelated repositories unless routing explicitly expands scope.

## Procedure

1. Map the API method and path to the route, controller, handler, listener, or scheduled entry point.
2. Trace service, domain, repository, client, adapter, and serialization calls relevant to the hypothesis.
3. Locate exception creation, propagation, translation, logging, and response mapping.
4. Record validation rules and the exact conditions that accept or reject input or state.
5. Identify transaction boundaries, isolation assumptions, commit and rollback behavior, and asynchronous work.
6. Record referenced schemas, tables, columns, business keys, trace fields, and timestamp fields.
7. Identify downstream service calls and their endpoint mapping, error mapping, retries, timeouts, circuit breakers, and fallbacks.
8. Inspect relevant cache, messaging, idempotency, scheduling, locking, and concurrency logic.
9. Find configuration names that affect behavior, but never expose secret values.
10. Compare competing paths and state what runtime evidence would distinguish them.

## Evidence output

Every `CODE-NNN` record must cite an exact repository-relative file path and inclusive line range. Quote only the minimal expression or behavior required to support the observation.

Separate:

- **Observed source fact:** what the inspected revision contains.
- **Possible behavior:** what could happen if its preconditions hold.
- **Runtime claim:** permitted only when supported by a separate runtime evidence ID.

Record generated sources, reflection, dynamic configuration, missing dependencies, and revision mismatch as limitations. Do not edit code, create patches, run formatters, or implement a fix.
