# Testing approach

## Test inventory

The figures are from `mvnw -B clean verify` on 2026-11-15/16.

| Project | Tests | What they cover |
|---|---|---|
| `url-shortener` | **73** | Unit, web slice, full-stack integration |
| `orchestrator` | **33** | Engine controls, audit, workspace, policy, the scenario flows, consistency between playbooks and product |
| Product API (pre-existing) | 16 | Unchanged; re-run as a regression check, still green |

## URL shortener

| Layer | Tests | Notes |
|---|---|---|
| Unit | `CodeGeneratorTest`, `BasicTargetUrlPolicyTest`, `UrlSafetyPolicyTest` (23 cases: loopback, RFC1918, link-local/cloud metadata, IPv6 ULA, encoded IPs, credentials, self-loop, blocklist), `LinkServiceTest` (collision retry, concurrent-insert race, bounded exhaustion, alias conflicts), `VisitorHasherTest`, `UserAgentClassifierTest`, `ClickRecorderTest` (batching, full buffer drops and counts, DB failure retries once then drops, never throws), `CreateRateLimitFilterTest` (burst, 429 + Retry-After, refill, per-client isolation, bounded memory) | Mockito; time is injected so tests are deterministic |
| Web slice | `LinkControllerTest` | `@WebMvcTest`: status codes, `Location`, validation messages, problem details, stable page shape |
| Integration | `RedirectFlowIntegrationTest`, `AnalyticsIntegrationTest` | `@SpringBootTest` on H2 in PostgreSQL mode **with the real Flyway migrations** and `ddl-auto: validate`, so the schema and the entities are checked against each other. The path covered is HTTP → service → JPA → DB → redirect → event → recorder → stats. |
| Live smoke (manual, run once) | `java -jar … --spring.profiles.active=local` plus curl | 201 create, 302 redirect, stats aggregation, 400 on `169.254.169.254`, 429 after the burst, 404 unknown code. The commands are in SETUP.md. |

## Orchestrator

| Area | Tests |
|---|---|
| Engine (`OrchestratorTest`, 18 tests) | <ul><li>Parallel execution: proven with a latch that only releases if both siblings run at once</li><li>Join ordering</li><li>Exponential backoff</li><li>No retry of PERMANENT failures; fallback instead</li><li>Rollback of failed attempts</li><li>Terminal failure → safe-stop → resume</li><li>Policy rework loop with feedback</li><li>Bounded rework → safe-stop</li><li>Interactive approval</li><li>Rejection → safe-stop → re-request</li><li>Delegated labelling</li><li>Approval timeout</li><li>Clarification pause and apply</li><li>Conditional skip</li><li>Replan invalidates only dependents and voids approvals</li><li>Kill switch</li><li>Budget</li><li>Labelled injected faults</li><li>Crash recovery</li><li>Audit chain intact</li></ul> |
| Graph (`WorkflowGraphTest`) | Topological order, parallel levels, downstream closure, readers; rejects cycles, unknown dependencies and invalid rework targets |
| Audit (`AuditLogTest`) | The chain verifies across reopen; editing or deleting an event is detected |
| Workspace (`WorkspaceTest`) | Rollback restores modified files and removes created ones; the journal survives a restart; escaping the sandbox and two stages writing the same file are blocked |
| Policy (`PolicyEngineTest`) | Clean change passes (including a regression for a `${...PASSWORD:...}` false positive found during development); each BLOCK rule fires; WARN thresholds |
| Scenarios (`ScenarioFlowTest`) | Greenfield, brownfield, ambiguous and safe-stop run through the **real** workflow, agents, gates and policy engine. Assertions cover skipped stages, retries and fallback, impact-analysis output, rework and remediation, clarification, replanning, and that every scripted human action is `delegated:` |
| Consistency (`PlaybookConsistencyTest`) | greenfield → brownfield → ambiguous composed = committed `url-shortener/`, byte for byte |

**What is stubbed in unit tests, and why.** `ScenarioFlowTest` replaces the `maven-verifier` agent with a stub, so the orchestrator suite takes seconds instead of minutes and does not need Maven to run nested inside Maven. The real build is exercised by the demos (`orch demo all`), which compile and test the sandbox project for every scenario. The `logs/build-*.log` files and `test-report` artifacts are the evidence. `.github/workflows/orchestrator-ci.yml` is configured to run the same demos and verify every audit chain, but it has not yet run: it first executes once the branch is pushed.

## How validation works inside the workflow

Every change the agents make passes these checks before a human sees the release:

1. **Build gate** (`build-green`): real compile and full test suite, with zero failures or errors and at least one test.
2. **Regression gate** (`tests-not-decreased`): the number of test methods may not drop below the impact-analysis baseline.
3. **Policy scan:** 5 blocking rules and 2 warning rules, run over every changed file.
4. **Contract check:** removing an endpoint is a breaking change, and every designed endpoint must be present.
5. **Release checklist:** 6 checks. Its output, `release-summary.md`, is what the human approves.

## Limitations and trade-offs

| Area | Limitation / trade-off | Mitigation or next step |
|---|---|---|
| Agent autonomy | Code content is SCRIPTED (playbook overlays), not generated. The LLM agent is a placeholder that always declines. | Gates and policies are independent of how code is produced. A P1 LLM implementer would plug in behind the same `Agent` interface and pass the same checks. |
| Requirement analysis | Rule-based: lexicon plus feature-catalog triggers. It cannot understand requirements outside the catalog. | It refuses to guess: an unknown capability leads to clarification, not an invented plan. An LLM analyst is P1. |
| Static analysis | Regex-based scanner (Spring conventions); no full type resolution | Enough for conventional Spring code. JavaParser or Spoon would be next. |
| Engine persistence | JSON files and one process per run. No distributed locking beyond the audit-file lock. | Fine for a single operator. A database plus leases would be needed for multiple workers. |
| Approvals | CLI only, no UI or notifications; identity is a `--by` string (no authentication) | P2: web UI with SSO identities |
| Analytics durability | The in-memory buffer loses unflushed clicks on a crash; clicks are dropped (and counted) under overload | Transactional outbox or Kafka (P2) |
| Rate limit / cache | Per instance, in memory | Redis or the gateway (P2) |
| URL safety | No DNS resolution on the request path, so DNS rebinding and hostnames that resolve to private IPs are not caught | Resolve and pin at redirect time, or use an egress proxy |
| Authentication | Link creation is unauthenticated (explicitly out of scope in the ambiguous scenario) | API keys or OAuth |
| Stats | Aggregated in Java over a per-link range (max 366 days) | Pre-aggregated daily rollup table if volume grows |
| PostgreSQL | Integration tests run on H2 in PostgreSQL mode. Docker was not available on the build machine, so Testcontainers was not used. | Add a Testcontainers suite in CI |
