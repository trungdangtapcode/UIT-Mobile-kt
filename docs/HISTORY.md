# Initial repository history

The initial unversioned implementation is organized into nine incremental commits.
The author dates represent the nine-day development period reported by the project
owner, October 19–27, 2026 (Asia/Tokyo). The commits are being assembled now, so
committer timestamps record their actual creation time. These are reconstructed
milestones, not recovered original per-edit timestamps.

| Author date | Commit | Scope |
| --- | --- | --- |
| 2026-10-19 | `chore: initialize Kotlin Android project and commit conventions` | Gradle wrapper/toolchain, a minimal runnable phone app, repository hygiene and commit hooks |
| 2026-10-20 | `feat(core): add domain models and academic scheduling rules` | Shared models, URL/time helpers, contracts, schedule and GPA rules, JVM tests |
| 2026-10-21 | `feat(moodle): implement secure Moodle transport and APIs` | Origin-restricted transport, Moodle discovery, scraping fallback, reads/writes and parser tests |
| 2026-10-22 | `feat(daa): parse academic schedules and transcripts` | Academic portal parsing, timetable changes, records and synthetic fixtures |
| 2026-10-23 | `feat(auth): add unified SSO and encrypted session storage` | Ephemeral UIT CAS/legacy WebView login and encrypted DataStore sessions |
| 2026-10-24 | `feat(data): persist portal snapshots and schedule background sync` | Room schema/store, offline repository, process graph, workers and data tests |
| 2026-10-25 | `feat(design): add UIT phone theme and cut-corner components` | Authentic transparent identity, licensed fonts/assets, phone shell and shared components |
| 2026-10-26 | `feat(features): implement phone study screens and messaging` | Five feature modules, single-column flows, native Back behavior and MVI tests |
| 2026-10-27 | `feat(app): integrate phone navigation and development workflows` | Composition root, account/navigation wiring, phone render tests and self-contained documentation |

The first commit contains a minimal app entry point. The final entry point replaces
that scaffold when its dependencies are present. Module registration is expanded
alongside the modules, so intermediate commits do not reference absent projects.
The presentation commit uses the current phone-only implementation; it does not
attempt to recreate every earlier visual experiment.

Each staged snapshot is exported to an isolated directory and validated before
committing. Commit bodies record the commands run for that snapshot. The final
snapshot runs the full build/check workflow and the phone UI suite. The working
source tree is retained throughout; the staged snapshots provide the incremental
history without modifying the existing completed implementation into temporary
scaffold versions.

The Git repository is rooted at `uit-mobile/`. The sibling `uit-cli` repository is
an upstream reference and is not nested in this history. The original plan is
copied into `docs/plan/` so that documentation is usable after a standalone clone.

## Verification results

All nine incremental snapshots assembled the debug app successfully. Their scoped
checks ran before each commit. The final snapshot also passed the full `check`
task and the phone UI suite: 72 distinct test cases, including 24 phone UI cases,
with no failures. The recorded gallery contains 22 current phone renders.

Repository checks confirmed that build/cache/signing/credential paths are ignored,
while the wrapper, Room schema, licensed assets and review images remain tracked.
Commit validation accepts the documented format and rejects invalid examples.
All local documentation links resolve inside a standalone checkout.
