# Fast Workflow

1. Preserve the service supplied by the user as the hard boundary. Resolve from the catalog only if no service was supplied; ask if ambiguous.
2. Select the first evidence lane from the symptom: validation uses code; traced HTTP 500 and timeouts use logs; incorrect data uses the database; a known exception location uses code.
3. Discover code only with `scripts/scoped_code_search.py`. Read only the most relevant candidate files.
4. Stop when the operation, code condition, needed runtime or data support, user-visible consequence, and non-speculative repair are connected.
5. Enforce all Fast budgets. Return `NEEDS_DEEP_INVESTIGATION`, the unresolved question, and Deep mode's next evidence action instead of expanding scope.

