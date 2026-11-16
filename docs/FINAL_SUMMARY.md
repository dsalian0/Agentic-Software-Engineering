# Final engineering summary

## What was built

1. **`orchestrator/`: an agentic SDLC orchestration system.**
   - It turns a free-text requirement into a reviewable release candidate.
   - It runs a declarative dependency graph of 16 stages:
     - requirements → clarification → planning → impact analysis
     - design → implementation, tests and docs in parallel
     - build, policy and contract verification in parallel
     - release readiness → publish
   - Humans approve at the high-impact points, and every transition is audited.
2. **`url-shortener/`: a runnable service.**
   - Create, look up and list short links; 302 redirect.
   - Asynchronous click analytics with daily, referrer and device statistics.
   - Target-URL safety rules and a per-client rate limit on link creation.
   - 73 tests.
   - Built as the cumulative output of the greenfield → brownfield → ambiguous scenarios.
3. **The existing Product API was not modified.** Its 16 tests still pass. Policy enforces this: any agent change outside `url-shortener/` is blocked.

## Mapping to the assignment

| Assignment requirement | Where |
|---|---|
| 4.1 Requirement understanding | `RequirementAnalystAgent` (normalized spec, vague-term and missing-criteria detection), `ClarificationApplierAgent`; ambiguous scenario |
| 4.2 Task decomposition | `PlannerAgent`: a task graph with dependencies and parallel groups, recorded in the `plan` artifact |
| 4.3 Codebase reasoning | `ImpactAnalyzerAgent` + `CodebaseScanner`: modified vs. added files, packages, endpoints, event data flows, baseline used by later gates |
| 4.4 Workflow orchestration | `Orchestrator` + `sdlc.yaml`: the dependency graph, gates, sequential and parallel paths, joins, shared versioned state with lineage, approvals, retries, fallback, rollback, rework, safe-stop, policy guardrails, audit chain, metrics, replanning. See [ARCHITECTURE.md](ARCHITECTURE.md). |
| 4.5 Engineering output | Shortener code, OpenAPI (springdoc), Flyway schema, unit and integration tests, docs; generated change notes per run |
| 4.6 Validation and risk control | Build, regression, policy and contract gates; release checklist; risk lists in the plan and impact report; [TESTING.md](TESTING.md) |
| 4.7 Controlled autonomy | Agents work only in a journaled sandbox, behind gates. Humans approve the plan, change control and release, answer clarifications, and can amend or stop at any time. Promotion into the repository is human-only. |
| 4.8 Final summary | This document |
| Deliverable: runnable end-to-end prototype | `orch demo all`, `java -jar url-shortener… --spring.profiles.active=local` |
| Deliverable: architecture overview | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Deliverable: three scenarios | [SCENARIOS.md](SCENARIOS.md) (plus a safe-stop demo) |
| Deliverable: setup | [SETUP.md](SETUP.md) |
| Deliverable: testing, limitations, trade-offs | [TESTING.md](TESTING.md) |

## Plan and rationale

The work was built in milestones so that an end-to-end demonstration existed as early as possible:

| Milestone | Delivered | Tests at the end |
|---|---|---|
| M1 | Shortener service | 73 green |
| M2 | Engine core with one unit test per control | 26 green |
| M3 | Agents, policies, playbooks, CLI | 33 green, including scenario flows and the consistency test |
| M4 | Real end-to-end demos with sandbox Maven builds; live HTTP smoke test of the service | All four demos completed |
| M5 | Documentation and CI | — |

**Key choices:**
- **Java for the engine:** a single toolchain.
- **Declarative graph with read/write contracts:** replanning and lineage follow from the data.
- **Journaled sandbox instead of git commits:** rollback per stage, and no commits made without approval.
- **Deterministic agents:** the MVP is reproducible.
- **Metrics derived from the audit log:** one source of truth.

The full table is in ARCHITECTURE §7.

## Real vs. simulated (explicit)

| Real, executed at run time | Scripted or simulated, and labelled |
|---|---|
| Graph scheduling, parallel execution, joins, persistence, resume, crash recovery | **Code content**: the implementer and test-writer apply pre-authored playbook files (mode `SCRIPTED`) |
| Requirement analysis, clarification mapping, planning, impact analysis, design extraction (deterministic rules) | **Injected transient build failure** (greenfield), `FAULT_INJECTED`, `injected=true` |
| Maven compile and full test suite of the sandbox project for every build stage | **Injected privacy defect** (brownfield), marked `[INJECTED DEFECT]` in `code-changes` |
| Policy scans, contract checks, exit gates, release checklist | **Human stand-ins** in demos: approvals, answers and the amendment are recorded as `delegated:*` |
| Rollback, rework, replanning, approval voiding, safe-stop, kill switch, budgets | **LLM doc writer**: a placeholder that reports itself unavailable. The fallback this triggers is real. |
| Hash-chained audit log, metrics, reports | `ScenarioFlowTest` stubs the Maven step for speed; the demos and CI do not |

The system does **not** claim autonomous code generation. What it demonstrates is the orchestration and governance around code production, and that layer is independent of whether the code comes from a playbook, an LLM or a person.

## Risks, trade-offs and validation

**Risks that were mitigated**
- **Parallel agents writing the same file:** the workspace blocks it (write-set conflict → `POLICY_BLOCK`).
- **Partial writes on failure or crash:** a per-stage journal provides rollback, and recovery runs on resume.
- **Audit tampering:** the hash chain is checked by `verify-audit`.
- **Scripted reviewers being mistaken for people:** the actor prefix and approval mode record this, and a test asserts it.
- **A policy false positive found during development** (the `${…PASSWORD:…}` placeholder) was fixed and covered by a regression test.
- **Stale approvals after a change:** voided automatically when their inputs are invalidated.

**Trade-offs**
- **Redirect availability over analytics completeness:** a bounded in-memory buffer, and drops are counted.
- **302 over 301:** analytics stay visible and targets stay mutable, at the cost of one extra hop.
- **Wave-safe failure handling:** the engine drains in-flight stages before handling a failure. This can waste work, such as a build that runs while policy blocks, in exchange for simpler and deterministic recovery.
- **File-based state:** simple and transparent, but limited to a single process.

**Validation summary**

| Suite | Result |
|---|---|
| url-shortener | 73 tests passing |
| orchestrator | 33 tests passing |
| Product API | 16 tests passing, unchanged |
| Demos (4) | Completed with real builds of 31, 47, 70 and 73 tests |
| Live HTTP smoke test | Passed |
| Audit chains | Intact for all runs |

## Assumptions

- Java 21 and Spring Boot for both projects, following the conventions in the repository's `AGENTS.md`.
- "Basic analytics" means total and daily clicks, unique visitors per day, top referrers and device families, with no geolocation.
- No authentication for link creation; this is recorded as out of scope by the ambiguous scenario.
- The local demo database is H2 in PostgreSQL mode, because Docker is not available on the development machine. PostgreSQL remains the production target.
- Approvals use a `--by` identity string; there is no SSO.

## Limitations and next steps

The detailed table is in [TESTING.md](TESTING.md#limitations-and-trade-offs).

| Priority | Next steps |
|---|---|
| P1 | LLM-backed analyst and implementer behind the existing `Agent` interface; their output passes the same gates, and budgets cover tokens |
| P1 | Testcontainers PostgreSQL suite in CI |
| P1 | JavaParser-based impact analysis |
| P1 | Approval identities |
| P2 | Web UI for approvals and run timelines |
| P2 | Outbox or Kafka for clicks; Redis for rate limits |
| P2 | Distributed workers with leases |
