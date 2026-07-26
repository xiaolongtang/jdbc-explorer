---
applyTo: "**"
---

# Project-wide Copilot instructions

## Working style

- First inspect the existing code, tests, package structure, and build files before making changes.
- Make the smallest correct change that solves the task.
- Do not refactor unrelated code.
- Prefer existing project conventions over introducing new patterns.
- Do not add production dependencies unless the task clearly requires it and explain why.
- When requirements are ambiguous, state the assumption before coding.

## Validation

Before saying the task is complete, run the most relevant available checks:

- Maven: `./mvnw test` or `./mvnw verify`
- Gradle: `./gradlew test` or `./gradlew build`
- If the command cannot be run, explain the reason and the risk.

## Code quality

- Favor simple, readable Java over clever abstractions.
- Keep methods small and cohesive.
- Use meaningful domain names instead of generic names like `data`, `manager`, `helper`, or `util`.
- Avoid broad exception swallowing. Preserve root causes in logs and error responses.
- Do not leak secrets, credentials, tokens, stack traces, or internal implementation details in API responses.

## Definition of done

For code changes, provide:

- Files changed
- Tests added or updated
- Commands run
- Remaining risks or assumptions
