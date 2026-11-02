<!--
### Sync Impact Report
- **Version Change**: Initial (Unversioned Template) → 1.0.0
- **Modified Principles**: N/A (Initial constitution creation)
- **Added Principles**:
  - 1. Technology Stack & Dependencies (Java 21, Maven, JUnit 5, JDK HTTP client/server, SQLite JDBC)
  - 2. Codebase Layout & Organization (src/, tests/, .specify/, specs/)
  - 3. Financial Precision & Representation (Integers in minor units)
  - 4. Transactional Atomicity (Balance changes and sequence keys in single DB transaction)
  - 5. Thread Safety & Concurrency Control (No unprotected shared mutable state, DB-enforced concurrency)
  - 6. Explicit Error Handling (No swallowed exceptions, distinct error types)
  - 7. Deterministic Fault Injection (Configurable seeded randomness)
  - 8. Comprehensive Automated Testing (Pass required for acceptance, reference requirement IDs)
  - 9. Requirement & Design Traceability (Stable IDs FR-1, FR-2, mandatory spec/plan mapping)
  - 10. Sequential Process & Spec-Driven Development (One task at a time, spec/plan updated first on gaps)
  - 11. Repository Security & Secret Exclusion (No secrets or DB files in repo)
- **Added Sections**: Core Principles, Governance
- **Removed Sections**: Template section placeholders ([SECTION_2_NAME], [SECTION_3_NAME])
- **Follow-up TODOs**: None
-->

# Banking System Constitution

## Core Principles

### 1. Technology Stack & Dependencies
The application MUST target Java 21 (Maven release level 21, using no language features or APIs newer than Java 21) and MAY be developed and built with OpenJDK 27. It MUST use Maven and JUnit 5. HTTP interactions MUST use exclusively the JDK's built-in `java.net.http.HttpClient` (client) and `com.sun.net.httpserver.HttpServer` (server). Persistence MUST use SQLite accessed via JDBC. Allowed external dependencies are strictly limited to the SQLite JDBC driver, JUnit 5, and at most one JSON library if justified by the architectural plan. No web frameworks are permitted.

### 2. Codebase Layout & Organization
Production source code MUST reside under `src/`. Automated test code MUST reside under `tests/`, and Maven MUST be configured to use `tests/` as its test source directory. Specifications and architectural plans MUST be maintained under `.specify/` and `specs/`.

### 3. Financial Precision & Representation
All monetary amounts MUST be represented as integers in minor currency units (e.g., cents) to ensure exact precision. The use of floating-point data types for monetary values is strictly prohibited.

### 4. Transactional Atomicity
Every account balance change and the corresponding update of the account's last executed sequence key MUST be performed within a single database transaction. Balance updates and sequence key increments commit together or fail together without exception.

### 5. Thread Safety & Concurrency Control
Unprotected shared mutable state is strictly prohibited. Server-side correctness for account data MUST be enforced through database transactions, not in-memory assumptions. Any in-memory state shared across threads (for example the client's key generator or the fault injector's configuration) MUST use explicit synchronization or thread-safe types, and the plan MUST name each such piece of state and how it is protected.

### 6. Explicit Error Handling
Exceptions MUST NOT be swallowed or silently logged without handling. The codebase MUST define distinct, explicit error types for network failures, timeouts, insufficient funds, and invalid requests.

### 7. Deterministic Fault Injection
The system MUST include a fault-injection mechanism that is configurable and deterministic, driven by seeded randomness. Fault scenarios MUST be completely reproducible in automated tests.

### 8. Comprehensive Automated Testing
No feature or task is accepted until all associated automated tests pass, including fault-injection and concurrency test suites. Every test MUST explicitly reference the stable requirement IDs (e.g., FR-1) it is designed to verify.

### 9. Requirement & Design Traceability
No production code MAY be authored without a corresponding functional requirement in the specification and a design element in the implementation plan. Functional requirements MUST use stable identifiers (FR-1, FR-2, ...), and commit messages MUST cite task and requirement IDs.

### 10. Sequential Process & Spec-Driven Development
Development MUST proceed strictly one task at a time, with full test verification following each task. If implementation reveals a gap in requirements or design, the specification or plan MUST be updated and committed prior to altering implementation code.

### 11. Repository Security & Secret Exclusion
API keys, credentials, secrets, or SQLite database files MUST NOT be committed to the repository under any circumstances. Repository ignore configurations MUST enforce this policy.

## Governance

This constitution governs all software design, implementation, and verification activities within the project. It supersedes all informal team practices or unwritten conventions.

### Amendment Procedure
This is a single-author project. An amendment is made by editing `.specify/memory/constitution.md`, updating the Sync Impact Report and version, and committing the change with a message that states what changed and why.

### Versioning Policy
This constitution follows Semantic Versioning (MAJOR.MINOR.PATCH):
- **MAJOR**: Backward-incompatible governance changes, principle removals, or fundamental redefinitions.
- **MINOR**: Addition of new principles, structural sections, or materially expanded governance rules.
- **PATCH**: Wording clarifications, formatting adjustments, typo fixes, or non-semantic refinements.

### Compliance Review
Before a task is accepted, the author MUST verify the specification, plan, and implementation against these principles. Code or design decisions that violate a MUST requirement are not accepted.

**Version**: 1.0.0 | **Ratified**: 2026-11-02 | **Last Amended**: 2026-11-02
