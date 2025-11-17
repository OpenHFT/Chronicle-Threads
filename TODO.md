# Chronicle-Threads - Repository TODO

**📋 Part of:** [Chronicle Architecture Documentation](../ARCH_TODO.md)
**Module Layer:** Layer 0 (Foundation)
**Priority:** 🔴 P0
**Last Updated:** 2025-11-16

## Purpose

This TODO file tracks work specific to Chronicle-Threads that feeds into the master [ARCH_TODO.md](../ARCH_TODO.md). It helps break down the architecture documentation work into manageable, repository-specific chunks.

## Related Main TODO Files

- [../ARCH_TODO.md](../ARCH_TODO.md) - Master architecture documentation roadmap
- [../TODO_INDEX.md](../TODO_INDEX.md) - Index of all TODO files
- [../ADOC_TODO.md](../ADOC_TODO.md) - AsciiDoc standardization (affects this module)

## Module Information for Architecture Overview

### Basic Information
- [x] **Module Name:** Chronicle-Threads
- [x] **Maven Artifact ID:** chronicle-threads
- [x] **Primary Purpose:** Provides high-performance event loop implementations, pauser strategies, and thread utilities for building low-latency, event-driven Java systems.
- [x] **Layer in Chronicle Stack:** Layer 0 (Foundation)
- [x] **Dependencies (Chronicle modules):** Chronicle-Core (`chronicle-core`), Java Thread Affinity (`affinity`).
- [x] **Key Classes/Interfaces:** `EventGroup`, `EventGroupBuilder`, `Pauser`, `PauserMode`, `Threads`.

### Architecture Information for ARCH_TODO.md Stage 3

**Feeds into:** ARCH_TODO.md Stage 3 - Module Deep Dives (ARCH-MOD-THREADS)

- [x] **Core Abstractions:** Event loops (`EventLoop`, `CoreEventLoop`, `MediumEventLoop`, `BlockingEventLoop`), event groups (`EventGroup`, `EventGroupBuilder`), pausers (`Pauser` and concrete implementations), thread and disk monitors (`ThreadMonitor`, `DiskSpaceMonitor`), and helper utilities in `Threads` / `EventLoops`.
- [x] **Interactions with other modules:** Integrates with Chronicle Core for handler interfaces, logging and lifecycle (`EventLoop`, `EventHandler`, `HandlerPriority`, `Jvm`) and with the Affinity library for CPU pinning; typically underpins Chronicle Queue, Chronicle Network, Chronicle Map and service frameworks that host their handlers on Chronicle Threads loops.
- [x] **Typical use cases:** Driving queue tailers and publishers, running trading or risk pipelines on dedicated fast threads, hosting maintenance / monitoring / replication tasks on auxiliary loops, and providing shared pauser and monitoring infrastructure for other Chronicle modules.
- [x] **Performance characteristics:** Optimised for very low latency and jitter via busy-spin pausers, single-writer loops and minimal allocation in the hot path; targets such as <= 10 us 99.99th percentile latency and zero allocations in `Pauser.pause()` / `reset()` are documented in `thread-performance-targets.adoc`.
- [x] **Design patterns used:** Single-writer event loop model, builder pattern for `EventGroup` configuration, strategy pattern for pausers and monitoring, and `ServiceLoader`-based extension points for disk-space notifications.

### Existing Documentation Audit

- [x] Check if `src/main/docs/architecture-overview.adoc` exists.
  - [x] If yes: Review quality (compare to Chronicle-Bytes standard) – uses standard front-matter, links to requirements and describes event loop topology, pauser strategy, monitoring plane, performance characteristics and trade-offs in sufficient detail for Stage 3.
  - [ ] If no: Note as gap for ARCH_TODO Stage 5.5 (N/A – architecture overview already present under the canonical filename).
- [x] Check if `src/main/docs/project-requirements.adoc` exists
  - [x] If yes: Review for ARCH_TODO Stage 1.75 (Requirements Overview) – `src/main/docs/project-requirements.adoc` summarises key `THR-*` requirements and complements the master catalogue in `src/main/adoc/project-requirements.adoc`.
  - [ ] If no: Note as gap for FUNC_TODO.md (N/A – both summary and full requirements catalogues exist).
- [x] Check if `src/main/docs/decision-log.adoc` exists – decision records currently live in `src/main/adoc/decision-log.adoc` and are linked from `README.adoc`.
  - [ ] If yes: Review for ARCH_TODO Stage 1.85 (Decision Log Overview)
  - [x] If no: Note as gap for DECISION_TODO.md – no duplicate `src/main/docs/decision-log.adoc`; coverage provided by `src/main/adoc/decision-log.adoc`, which may be cross-linked or mirrored if Stage 1.85 requires a docs-tree copy.
- [x] Check if `README.adoc` provides good module overview – README explains event loops, handlers, pausers and lifecycle, and links to requirements and decision log.
- [x] Check if `AGENTS.md` exists and follows canonical template – module-specific `AGENTS.md` is present and aligned with `canonical-AGENTS.md`.

### Documentation Gaps (for ARCH_TODO Stage 5.5)

**Missing Documentation:**
- [x] Architecture overview? [Y/N] Y – `src/main/docs/architecture-overview.adoc`.
- [x] Requirements documentation? [Y/N] Y – `src/main/docs/project-requirements.adoc` plus full specification in `src/main/adoc/project-requirements.adoc` and summary tables in `src/main/docs/functional-requirements.adoc`.
- [x] Decision log? [Y/N] Y – `src/main/adoc/decision-log.adoc` (no separate `src/main/docs/decision-log.adoc`).
- [x] Security review? [Y/N] Y – `src/main/docs/thread-security-review.adoc`.
- [x] Testing strategy? [Y/N] Y – testing and ownership guidance is documented in `src/main/docs/thread-safety-guide.adoc` and verification columns in `functional-requirements.adoc`.
- [x] Performance targets? [Y/N] Y – `src/main/docs/thread-performance-targets.adoc`.

**Documentation Quality Issues:**
- [ ] Missing `:toc:`, `:lang: en-GB`, or `:source-highlighter: rouge`? (Spot-check of AsciiDoc files under `src/main/**` shows these attributes present; keep as a guard-rail for future changes.)
- [ ] Manual section numbering instead of `:sectnums:`? (No headings of the form `== 1.` were found; existing docs use implicit numbering.)
- [ ] Broken cross-references? (Quick manual checks of README and primary docs succeeded; a deeper automated link check is still pending.)
- [ ] Outdated information? (Docs have been refreshed in 2025; ongoing review is required as new features land.)

## Requirements for Architecture Overview (ARCH_TODO Stage 1.75)

**Feeds into:** Requirements Overview consolidation

- [x] **Identify key functional requirements:** Key requirements include builder-based configuration for loops and groups (`THR-FN-001`, `THR-FN-002`, `THR-FN-003`), dynamic handler registration and priorities (`THR-FN-004`, `THR-FN-005`, `THR-FN-006`, `THR-FN-008`), standard and pluggable pauser strategies (`THR-FN-010`..`THR-FN-012`), and affinity / NUMA-aware placement (`THR-FN-015`, `THR-FN-017`).
- [x] **Identify key non-functional requirements:**
  - [x] Performance targets: Latency, jitter, throughput, allocation and CPU utilisation targets are captured as `THR-NF-P-014`, `THR-NF-P-027`..`THR-NF-P-031` in `project-requirements.adoc` and expanded in `thread-performance-targets.adoc`.
  - [x] Security obligations: Security-related risks and mitigations are described in `thread-security-review.adoc`, referencing requirements such as `THR-FN-004`, `THR-FN-015`, `THR-FN-017`, `THR-OPS-020`, `THR-OPS-023`, `THR-OPS-025`, `THR-NF-O-019` and `THR-NF-O-021`.
  - [x] Operability requirements: Operational and observability requirements (e.g. CPU isolation, loop-block monitoring, telemetry export, lifecycle management) are covered by `THR-NF-O-*` and `THR-OPS-*` entries in `project-requirements.adoc` and elaborated in `operational-controls.adoc` and `thread-safety-guide.adoc`.
- [x] **Map requirements to architecture patterns:** Single-threaded event loops and handler priorities implement the single-writer pattern for deterministic, lock-free handler state; pauser requirements map to a strategy pattern that balances CPU usage and latency; affinity requirements map to explicit core pinning and NUMA-aware deployment; monitoring and operational requirements map to a dedicated monitor loop and the `DiskSpaceMonitor` / `NotifyDiskLow` extension points.

## Decisions for Architecture Overview (ARCH_TODO Stage 1.85)

**Feeds into:** Decision Log Overview consolidation

- [x] **Identify key architectural decisions:**
  - [x] Decision ID (if in decision-log.adoc): THR-FN-001, THR-NF-P-002, THR-OPS-003, THR-DOC-004.
  - [x] Brief description: Single-threaded event loops for handler execution; busy-spin pausers for latency-critical fast threads; background disk-space monitoring via `DiskSpaceMonitor`; Nine-Box taxonomy for requirements and decisions.
  - [x] Rationale: Simplify concurrent handler code and provide predictable latency, minimise wake-up time for fast loops, surface Chronicle-aware storage risk early without imposing shutdown policy, and standardise traceability across Chronicle modules.
  - [x] Alternatives considered: Multi-threaded event loops or actor-per-handler designs; yielding or sleeping pausers as defaults; relying solely on external disk monitoring or more intrusive built-in actions; ad-hoc or purely sequential identifier schemes.
- [x] **Identify decision patterns used:**
  - [x] Off-heap memory? [N – Chronicle Threads itself does not own off-heap structures; off-heap patterns are applied primarily in consumers such as Chronicle Queue and Chronicle Map.]
  - [x] Single writer principle? [Y – each `EventLoop` is single-threaded for handler execution (THR-FN-001), so handler-owned state follows a single-writer model.]
  - [x] Reference counting? [Y – handlers are expected to follow Chronicle Core `ReferenceCounted` practices for dependent resources; tests such as `ThreadsTestCommon` assert references are released on shutdown.]
  - [x] Flyweight pattern? [N – no explicit flyweight types; reuse is achieved via shared pauser implementations and monitoring infrastructure rather than classic flyweights.]

## Glossary Terms (ARCH_TODO Stage 1.5)

**Feeds into:** Cross-module glossary

- [x] **Module-specific terms to include in glossary:**
  - [x] Term 1: Fast Thread – a thread pinned to an isolated CPU core and used to run latency-critical event loops with busy pausers.
  - [x] Term 2: Loop-Block Monitor – a monitoring loop or handler that measures `EventHandler` execution time and logs stack traces when thresholds are exceeded.
  - [x] Term 3: Pauser – a strategy object that controls how an idle event loop waits between handler invocations (for example, busy-spin, yield, sleep), configured via `PauserMode`.


## ISO 9001 Quality Management Considerations

**Reference:** [../COMPLIANCE_QUICK_REFERENCE.md](../COMPLIANCE_QUICK_REFERENCE.md)

### Design Inputs (ISO 9001 Clause 8.3.3)
- [x] **Functional requirements documented?**
  - [x] Location: `src/main/docs/project-requirements.adoc` (summary) and `src/main/adoc/project-requirements.adoc` (full catalogue).
  - [x] Requirements use Nine-Box taxonomy? (`THR-FN-*` identifiers consistent with Nine-Box guidance in `AGENTS.md`.)
  - [x] Requirements are testable and verifiable? Requirements are expressed as behavioural statements with associated verification notes and examples; `functional-requirements.adoc` adds explicit verification columns.
- [x] **Non-functional requirements documented?**
  - [x] Performance requirements (THREADS-NF-P-NNN) – recorded as `THR-NF-P-*` in `project-requirements.adoc` and detailed in `thread-performance-targets.adoc`.
  - [x] Security requirements (THREADS-NF-S-NNN) – captured through security-related THR IDs in `thread-security-review.adoc` and cross-referenced from `project-requirements.adoc`.
  - [x] Operability requirements (THREADS-NF-O-NNN) – described by `THR-NF-O-*` and `THR-OPS-*` entries and expanded in `operational-controls.adoc` and `thread-safety-guide.adoc`.

### Design Outputs (ISO 9001 Clause 8.3.5)
- [x] **Architecture documented?**
  - [x] Location: `src/main/docs/architecture-overview.adoc`.
  - [x] Describes key components and their interactions? Yes – covers event loop topologies, handler lifecycle, pauser strategies, monitoring plane, performance characteristics, trade-offs and integration touchpoints.
  - [x] Includes interface specifications? High-level contracts for `EventGroup`, `EventLoop`, `Pauser`, monitoring hooks and disk-space monitor are described, with detailed signatures delegated to Javadoc.
- [x] **APIs and interfaces specified?**
  - [x] Public API documented (JavaDoc)? Yes – public types in `net.openhft.chronicle.threads` are documented and published via javadoc.io, linked from `README.adoc`.
  - [x] Integration points with other modules described? Yes – integration with Chronicle Queue, Chronicle Map, Chronicle Network and the Affinity library is described in README and architecture / operational docs.

### Design Verification (ISO 9001 Clause 8.3.4)
- [ ] **Requirements traceable to tests?**
  - [ ] Test classes reference requirement IDs in comments/docs? Test classes do not yet embed THR identifiers, but `functional-requirements.adoc` now maps each requirement group to representative tests (for example `EventGroupTest`, `StopVCloseTest`, `PauserTest`, `LongPauserTest`, `EventGroupBadAffinityTest`), providing a documented trace from requirements to executable checks.
  - [ ] Coverage: What % of requirements have corresponding tests? Overall coverage has not been quantified; a future task is to produce a simple matrix or report that confirms which THR requirements lack direct test references.
- [x] **Test strategy documented?**
  - [x] Unit test approach – described at a high level in `thread-safety-guide.adoc` and implied by verification notes in requirements docs.
  - [x] Integration test approach – covered by examples and integration scenarios in `project-requirements.adoc` and `functional-requirements.adoc`.
  - [x] Performance test approach (if applicable) – detailed in `thread-performance-targets.adoc`, including benchmark methodology and regression gates.
- [ ] **Code review evidence?**
  - [ ] PR review process followed?
  - [ ] Review comments addressed?

### Design Changes (ISO 9001 Clause 8.3.4)
- [x] **Architectural decisions documented?**
  - [x] Location: `src/main/adoc/decision-log.adoc`.
  - [x] Decisions include context, alternatives, rationale? Yes – each THR decision record follows the standard template with context, decision, alternatives, rationale and consequences.
  - [x] Impact of changes assessed? Decision records and requirements cross-references note expected impacts; individual PRs should continue to call these out.
- [ ] **Change history maintained?**
  - [ ] Git commit messages describe rationale?
  - [ ] Breaking changes documented in release notes?

## ISO 27001 Information Security Considerations

**Reference:** [../ARCHITECTURE_RESEARCH_GUIDE.md](../ARCHITECTURE_RESEARCH_GUIDE.md) - Security Research Topics

### Secure Coding (ISO 27001 Control A.8.28)
- [ ] **Input validation implemented?**
  - [ ] Where are untrusted inputs received? Entry points are primarily application-provided `EventHandler` implementations and configuration via JVM system properties and builder parameters; Chronicle Threads itself does not parse network payloads or user-facing data.
  - [ ] How are malformed inputs handled? Invalid configuration values are rejected or fall back to documented defaults; validation of business data is delegated to callers such as Chronicle Queue and application handlers and should be documented in those modules’ security guides.
  - [ ] Size limits enforced? Loop-block monitoring and pauser configuration include threshold and timeout limits; data-size limits for payloads are enforced by upstream components rather than by Chronicle Threads.
- [ ] **Bounds checking implemented?**
  - [ ] Buffer overflow prevention mechanisms? Chronicle Threads does not manage raw buffers directly; off-heap and buffer-level bounds checking is provided by Chronicle Core and related libraries.
  - [ ] Array access validation? Any internal array usage relies on JVM bounds checks; bulk data structures and serialisation are handled in dependent modules.
  - [ ] Off-heap memory bounds checked? Off-heap access is delegated to Chronicle Core (for example `BytesStore`), which provides bounds checking and reference-counted lifecycle management.
- [ ] **Static analysis performed?**
  - [ ] Checkstyle violations reviewed? (To be covered by repository-wide Checkstyle runs and documented in code-quality tasks below.)
  - [ ] SpotBugs security patterns checked? (To be covered by repository-wide SpotBugs runs and documented in code-quality tasks below.)
  - [ ] Suppressions justified and documented? (Policy to be documented alongside Checkstyle / SpotBugs reports once generated.)

### Access Control (ISO 27001 Control A.8.3)
- [ ] **Access restrictions implemented?**
  - [ ] Are there authentication/authorization mechanisms? Chronicle Threads itself does not implement authentication or authorisation; it runs with the privileges of the hosting JVM.
  - [ ] If yes, where and how are they implemented? Access control for management endpoints or configuration changes must be implemented by the hosting application or platform and described in those components’ documentation.
  - [ ] Principle of least privilege followed? Handler admission and configuration updates should be restricted to trusted code paths and reviewed under least-privilege principles, as described in `thread-security-review.adoc`.
- [ ] **Privileged operations identified?**
  - [ ] Which operations require elevated privileges? Operations that mutate JVM arguments, thread affinity or file-system paths (for example disk-space monitoring targets) require elevated privileges.
  - [ ] How are they protected? Protection is handled by OS and JVM-level controls; operational run-books should ensure only trusted users can modify launch scripts, system properties or deployment descriptors.

### Cryptographic Controls (ISO 27001 Control A.8.24)
- [ ] **Cryptography usage identified?**
  - [ ] Is encryption used? Chronicle Threads does not perform encryption directly; any cryptography is provided by libraries used within handlers or dependent modules (e.g. Chronicle Network, TLS stacks).
  - [ ] Is hashing used? Any hashing of data is implemented by upstream components; Chronicle Threads itself focuses on scheduling and does not define hashing algorithms.
  - [ ] Is TLS/SSL used? TLS/SSL configuration belongs to networking layers that may host their handlers on Chronicle Threads; relevant settings are documented in those modules (e.g. Chronicle Network).
- [ ] **Key management?**
  - [ ] How are cryptographic keys managed? Key management is out of scope for Chronicle Threads and must be handled by application or infrastructure key-management systems.
  - [ ] Are keys hardcoded? Any hardcoded keys would reside in application code or other modules rather than in Chronicle Threads; security reviews should confirm this as part of system-level assessments.

### Network Security (ISO 27001 Control A.8.22)
- [ ] **Network communication security?**
  - [ ] Does this module communicate over network? Chronicle Threads does not open sockets or manage network connections directly.
  - [ ] If yes, is communication encrypted? N/A at the module level; encryption is provided by networking libraries that may use Chronicle Threads for scheduling.
  - [ ] How are network endpoints authenticated? N/A for this module; endpoint authentication is handled in calling components (e.g. Chronicle Network, application gateways).
- [ ] **Network configuration?**
  - [ ] Secure defaults configured? Network configuration defaults are defined outside Chronicle Threads; operational guidance for those modules should be followed.
  - [ ] Insecure protocols disabled? Disabling insecure protocols (e.g. outdated TLS versions) is the responsibility of networking components; Chronicle Threads’ role is to schedule their handlers.

### Vulnerability Management (ISO 27001 Control A.8.8)
- [ ] **Known vulnerabilities?**
  - [ ] Any open security issues in GitHub? (Tracked at organisation level across all Chronicle repositories; see central security processes.)
  - [ ] Any CVEs against dependencies? (Managed via Chronicle BOMs and dependency scanning; Chronicle Threads should consume approved BOM versions.)
- [ ] **Security testing?**
  - [ ] Fuzz testing performed? (If required, should be executed against applications built on Chronicle Threads rather than the Threads library alone.)
  - [ ] Security-specific test cases? Unit and integration tests within Chronicle Threads focus on correctness and performance; security-specific tests are typically implemented at the system level.
  - [ ] Penetration testing performed? (Performed against deployed systems rather than individual libraries; any findings involving Chronicle Threads should be documented in application security reports.)

### Security Documentation
- [x] **Security review documented?**
  - [x] Location: `src/main/docs/thread-security-review.adoc`.
  - [x] Threat model documented? Yes – identifies risks such as malicious handler registration, misconfigured affinity strings and disabled monitoring, with associated mitigations.
  - [x] Security controls described? Yes – documents configuration hardening, least-privilege handler admission and operational checks aligned with THR and OPS requirements.
  - [x] Known limitations documented? Yes – notes reliance on hosting applications for authentication / authorisation and external telemetry systems for alerting.

## Improvement Tasks (ARCH_TODO Stage 5.5)

**Feeds into:** Improve Existing Module Documentation

### High Priority
- [x] Create missing architecture-overview.adoc (if needed) – Chronicle-Threads now uses the canonical `architecture-overview.adoc` filename for its runtime overview document.
- [x] Add missing front-matter to existing docs – primary AsciiDoc files under `src/main/docs` and `src/main/adoc` already include `:toc:`, `:lang: en-GB` and `:source-highlighter: rouge`.
- [x] Fix broken cross-references – local links between README, requirements, architecture, operational, security and performance docs have been aligned and validated for the Chronicle-Threads module. A deeper repository-wide link validation remains outside this module’s scope.
- [x] Add `:sectnums:` where appropriate – applied to narrative docs such as requirements, performance targets, operational controls and security review.

### Medium Priority
- [x] Expand brief architecture docs (if < 75 lines) – `architecture-overview.adoc` has been extended with performance characteristics and trade-off discussions; future feature-specific sections can be added as needed.
- [x] Add "Trade-offs and Alternatives" section (following Chronicle-Bytes pattern) – implemented as a dedicated section in `architecture-overview.adoc` that contrasts single-threaded loops with thread pools, busy versus balanced pausers, centralised monitoring versus minimal instrumentation, and per-module groups versus JVM-wide executors.
- [x] Add performance characteristics section – `architecture-overview.adoc` now summarises latency, jitter, throughput, allocation profile and benchmark methodology and links to `thread-performance-targets.adoc` for full details.
- [ ] Create decision log entries for undocumented decisions – future features should add THR decision records as they are designed.

### Low Priority
- [ ] Add diagrams (PlantUML or draw.io)
- [x] Create example code snippets – README now includes an advanced example showing multiple `EventGroup` instances with different pauser modes and affinity bindings for trading and operational workloads.
- [ ] Expand requirements documentation – requirements are relatively complete; future gaps should be captured with new THR IDs as they are discovered.
- [x] Add cross-references to other module docs – README and architecture docs now cross-link Chronicle-Threads requirements, architecture, operational controls, security review, thread-safety guidance, performance targets and system properties within this module; further cross-module integration guides can be added separately.

## Code Quality Tasks

**Reference:** [../QUALITY_PLAYBOOK.md](../QUALITY_PLAYBOOK.md)

- [ ] Run Checkstyle scan and document violations
- [ ] Run SpotBugs scan and document issues
- [ ] Identify any code review follow-ups from CODE_REVIEW_STATUS.md

## Notes

[Add any module-specific notes, blockers, or context here]

## Completion Checklist

Before marking this repository's contribution to ARCH_TODO as complete:

- [x] All "Module Information" sections filled out
- [x] Existing documentation audited
- [x] Requirements identified for ARCH_TODO Stage 1.75
- [x] Decisions identified for ARCH_TODO Stage 1.85
- [x] Glossary terms identified for ARCH_TODO Stage 1.5
- [x] Documentation gaps documented
- [x] Improvement tasks prioritized
- [x] Information contributed to relevant ARCH_TODO stages

---

**When complete, update:** [../ARCH_TODO.md](../ARCH_TODO.md) Stage 3 tracking matrix
