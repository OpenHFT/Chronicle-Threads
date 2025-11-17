# Chronicle-Threads CI Data Review TODO

This checklist records the CI data that should be gathered and linked for Chronicle-Threads to support:

- Architecture and requirements documentation (for example `architecture-overview.adoc`, `project-requirements.adoc`, `thread-performance-targets.adoc`).
- Quality and coverage tracking (JaCoCo, static analysis).
- ISO 9001 / ISO 27001 traceability, as referenced in `Chronicle-Threads/TODO.md` and repository-wide compliance guides.

Tick items once evidence exists and is linked from CI dashboards or artefacts.

---

## 1. Build and Test Outcomes

- [ ] Record the outcome of `mvn -q clean verify` including Chronicle-Threads (success / failure, duration).
  - [ ] CI job name or identifier:
  - [ ] Build log URL:
  - [ ] Recorded wall-clock duration:
- [ ] Store unit and integration test reports for Chronicle-Threads.
  - [ ] Archive `Chronicle-Threads/target/surefire-reports/*.xml` (unit tests).
  - [ ] Archive `Chronicle-Threads/target/failsafe-reports/*.xml` (integration tests, if used).
- [ ] Capture any module-specific Maven profiles or flags used in CI (for example `-pl Chronicle-Threads -am`).

## 2. Coverage and Requirement Traceability

- [ ] Publish JaCoCo line and branch coverage reports for Chronicle-Threads.
  - [ ] Ensure JaCoCo is enabled and archive `Chronicle-Threads/target/site/jacoco/*`.
  - [ ] Record achieved line and branch coverage for `net.openhft.chronicle.threads` packages.
- [ ] Track coverage trends across recent builds.
  - [ ] Configure CI dashboards to show coverage trends for Chronicle-Threads.
- [ ] Maintain a lightweight mapping from key requirement IDs (`THR-FN-*`, `THR-NF-P-*`, `THR-NF-O-*`, `THR-OPS-*`) to representative tests.
  - [ ] Use `src/main/docs/functional-requirements.adoc` as the canonical human-readable mapping.
  - [ ] Optionally generate a machine-readable mapping (CSV or JSON) as a CI artefact.
- [ ] Derive and document the percentage of requirements that have at least one mapped test, with special attention to performance and operability requirements.

## 3. Static Analysis and Code Quality

- [ ] Run Checkstyle for Chronicle-Threads and store report artefacts.
  - [ ] CI job name:
  - [ ] Checkstyle report URL or path:
  - [ ] Summary of new versus baseline violations:
- [ ] Run SpotBugs for Chronicle-Threads and store report artefacts (including security-category issues).
  - [ ] CI job name:
  - [ ] SpotBugs report URL or path:
  - [ ] Summary of critical / high-priority findings:
- [ ] Collect a list of active Checkstyle / SpotBugs suppressions with file, rule and justification.
  - [ ] Generate or maintain a suppression inventory as a CI artefact for review.

## 4. Performance and Benchmark Evidence

Reference: `src/main/docs/thread-performance-targets.adoc` (THR-NF-P-014, THR-NF-P-027..THR-NF-P-031).

- [ ] Identify benchmark or JLBH jobs that exercise Chronicle-Threads hot paths (for example event loop and pauser benchmarks).
  - [ ] List benchmark classes or harnesses:
- [ ] Run performance benchmark suites on representative hardware for Chronicle-Threads.
  - [ ] Record hardware profile (CPU model, core count, NUMA layout, RAM).
  - [ ] Record JDK version and JVM flags used for benchmarks.
- [ ] Capture benchmark results as CI artefacts (JSON, CSV or HTML) and relate them to the THR performance requirements.
  - [ ] For each benchmark run, record key metrics: latency percentiles, jitter, throughput, allocation rate, CPU utilisation.
- [ ] Track historical benchmark results to detect regressions and link any significant changes to decision-log entries (`THR-NF-P-*`, `THR-OPS-*`).

## 5. Security and Dependency Data

Reference: `src/main/docs/thread-security-review.adoc`, `COMPLIANCE_QUICK_REFERENCE.md`.

- [ ] Run dependency vulnerability scans that include Chronicle-Threads as part of the multi-module build.
  - [ ] Tool name (for example Dependabot, Snyk, OWASP Dependency-Check):
  - [ ] Scan report URL:
  - [ ] High / critical CVEs affecting Chronicle-Threads dependencies:
  - [ ] Mitigation or upgrade plan for each relevant CVE:
- [ ] Ensure CI logs and configuration artefacts are scanned for accidental secrets or credentials.
  - [ ] Secret-scanning tool name and configuration:
  - [ ] Summary of scan status per build:
- [ ] Summarise security review evidence for a given release of Chronicle-Threads.
  - [ ] Link to `thread-security-review.adoc` and any release-specific security notes.

## 6. CI Artefact Publishing for Documentation

Reference: `src/main/docs/architecture-overview.adoc`, `project-requirements.adoc`, `functional-requirements.adoc`, `operational-controls.adoc`, `thread-safety-guide.adoc`, `thread-performance-targets.adoc`.

- [ ] Render key AsciiDoc documents for Chronicle-Threads to HTML or PDF in CI.
  - [ ] Architecture overview (`architecture-overview.adoc`).
  - [ ] Requirements (`project-requirements.adoc`, `functional-requirements.adoc`).
  - [ ] Operational controls (`operational-controls.adoc`).
  - [ ] Thread-safety guide (`thread-safety-guide.adoc`).
  - [ ] Security review (`thread-security-review.adoc`).
  - [ ] Performance targets (`thread-performance-targets.adoc`).
- [ ] Publish rendered documentation as CI artefacts or to a static site so that architecture and compliance reviews can link to stable URLs.

## 7. Process and Review Evidence

- [ ] Confirm that PRs touching Chronicle-Threads require at least one approving review and a passing CI build (tests, static analysis, benchmarks where applicable).
- [ ] Encourage or require PR descriptions to reference relevant THR requirement IDs (for example `THR-FN-006`, `THR-NF-P-027`) when behaviour or performance characteristics change.
- [ ] Record notable changes in performance, security and compatibility for Chronicle-Threads releases and cross-reference requirement or decision-log IDs.
  - [ ] Link release notes and CI data snapshots for these changes.

## 8. Index of CI Jobs and Data Locations

- [ ] Maintain a short index (in this file or a companion adoc) that points to:
  - [ ] The CI jobs that produce each category of data above (build/test, coverage, static analysis, benchmarks, security scans, doc rendering).
  - [ ] Where to find the latest reports for those jobs (dashboards, artefact URLs).
- [ ] Review and update this index when new CI jobs are added or existing ones are renamed.

