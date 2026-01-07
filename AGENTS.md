# AGENTS.md

## Scope
- Chronicle Threads repository guidance for contributors and agents.

## Build and test
- Preferred full check:
  - `mkdir -p logs`
  - `mvn verify -l logs/mvn-verify.log`
- Test example:
  - `mvn -Dtest=ClassName test -l logs/mvn-test.log`
- Review logs:
  - `rg -n '^\[(WARNING|ERROR)\]|SLF4J\(W\)|\bWARNING:|\bwarning:' logs/mvn-verify.log`
- Do not commit logs/.

## Constraints
- Java baseline: 8 (avoid newer language features).
- Source files must stay ISO-8859-1 (code points 0-255). Prefer ASCII; avoid smart quotes and non-breaking spaces.
- Preserve public APIs unless explicitly requested.
- Treat warnings as defects; keep logs clean.
- Avoid extra allocations or synchronisation on hot paths.
- Event handlers should avoid blocking and keep work chunks small; use pausers rather than sleeping.

## Docs and review checklist
- Keep AsciiDoc, tests, and code synchronised; update `.adoc` files when behaviour changes.
- Javadoc must add behavioural contracts, edge cases, thread safety, units, or performance notes.
- For large mechanical changes, declare the transformation rule and keep it consistent.

## References
- `src/main/adoc/decision-log.adoc` and `src/main/adoc/project-requirements.adoc`.
- `src/main/docs/project-requirements.adoc` and `src/main/docs/thread-*.adoc`.
- `OpenHFT/docs/Company-Wide-Tagging.adoc` for tagging and AsciiDoc conventions.
