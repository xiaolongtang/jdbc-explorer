---
description: Spring Boot development standards for Java and Kotlin services
applyTo: "**/*.java,**/*.kt,**/pom.xml,**/build.gradle,**/build.gradle.kts,**/application*.yml,**/application*.yaml,**/application*.properties"
---

# Spring Boot development instructions

## Baseline

- Target Spring Boot 3.x+ unless the repository clearly uses another version.
- Use Java 17+ language features only when the project build supports them.
- Use `jakarta.*` imports for Spring Boot 3+ projects. Do not introduce legacy `javax.*` APIs unless the project is still on Spring Boot 2.x.
- Follow the existing build tool: Maven for `pom.xml`, Gradle for `build.gradle` or `build.gradle.kts`.

## Project structure

- Organize new code by domain or feature when the project already follows that style.
- Keep controllers thin, services business-focused, repositories data-focused, and configuration isolated.
- Do not expose JPA entities directly through REST APIs. Use request/response DTOs.
- Keep DTOs explicit. Avoid returning maps or raw objects for public API responses.

## Dependency injection

- Use constructor injection for required dependencies.
- Dependency fields should be `private final`.
- Do not use field injection.
- Prefer package-private constructors and methods only when that matches the project style and improves testability.

## Configuration

- Use externalized configuration via `application.yml`, `application.yaml`, `application.properties`, environment variables, or command-line properties.
- Use `@ConfigurationProperties` for grouped, type-safe configuration.
- Do not hardcode secrets, URLs, credentials, API keys, or environment-specific values.
- Keep profile-specific configuration in `application-{profile}.yml` or equivalent files.

## Web/API layer

- Use `@RestController` for JSON APIs.
- Use DTOs with validation annotations such as `@Valid`, `@NotNull`, `@NotBlank`, `@Size`, `@Min`, and `@Max`.
- Return appropriate HTTP status codes.
- Use `ResponseEntity` when status, headers, or empty responses need to be explicit.
- Implement consistent error responses with `@ControllerAdvice` and `@ExceptionHandler`.
- Do not return raw exception messages to clients.

## Service layer

- Put business logic in `@Service` classes.
- Services should be stateless unless there is a clear reason.
- Use `@Transactional` at the service layer for write operations or multi-step read consistency.
- Mark read-only transactions with `@Transactional(readOnly = true)` when appropriate.
- Avoid calling transactional methods through `this`, because proxy-based transaction advice may not apply.

## Data/JPA

- Use Spring Data repositories for standard persistence operations.
- Avoid N+1 queries. Consider fetch joins, entity graphs, projections, or query-specific DTOs.
- Prefer pagination for collection endpoints.
- Avoid eager relationships by default; justify any `FetchType.EAGER`.
- Be careful with bidirectional relationships in JSON serialization.
- Use database migrations if the project already uses Flyway or Liquibase.
- Do not generate schema-changing code without tests and migration scripts.

## Security

- Use Spring Security for authentication and authorization when security is in scope.
- Prefer method-level authorization for business rules.
- Do not disable CSRF unless the API is stateless and the security model justifies it.
- Validate all external input.
- Use parameterized queries, Spring Data JPA, or `NamedParameterJdbcTemplate`; never concatenate user input into SQL.
- Do not log passwords, tokens, authorization headers, PII, or secrets.

## Logging and observability

- Use SLF4J. Do not use `System.out.println`.
- Use parameterized logging: `log.info("Created order {}", orderId)`.
- Include useful context such as IDs and operation names, but avoid sensitive data.
- For production-facing services, prefer Actuator health, metrics, and structured logs when the project already includes observability dependencies.

## Testing

- Add or update tests for behavior changes.
- Use JUnit 5.
- Use Mockito for isolated service tests when appropriate.
- Use `@WebMvcTest` for controller slice tests.
- Use `@DataJpaTest` for repository tests.
- Use `@SpringBootTest` only when full application context integration is needed.
- Use Testcontainers for database or broker integration tests when the project already uses it or when embedded/in-memory substitutes would hide production issues.
- Test positive, negative, validation, authorization, and edge cases for API changes.

## Build and verification

- Maven: prefer `./mvnw test`, `./mvnw verify`, or `./mvnw spring-boot:run`.
- Gradle: prefer `./gradlew test`, `./gradlew build`, or `./gradlew bootRun`.
- Do not claim tests passed unless they were actually run.
