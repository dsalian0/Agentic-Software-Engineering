# Scenarios

Each scenario runs the same governed workflow (`orchestrator/workflows/sdlc.yaml`) with a different playbook (`orchestrator/playbooks/<id>/scenario.yaml`). The scenarios build on each other:

```
greenfield  -> url-shortener v1 (core links)                31 tests
brownfield  -> v1 + click analytics                         47 tests
ambiguous   -> v2 + URL safety (+ rate limit after replan)  73 tests  == committed url-shortener/
```

`PlaybookConsistencyTest` checks that layering these three outputs reproduces the committed `url-shortener/` exactly.

Run any scenario with:

```bash
java -jar orchestrator/target/orchestrator.jar demo <greenfield|brownfield|ambiguous|safe-stop|all> --offline
```

> **What "demo" means.** The demo driver stands in for the humans: it approves checkpoints, answers clarification questions and submits the amendment. Every one of those actions is recorded as `delegated:demo-reviewer` or `delegated:product-owner`, never as a person.
>
> Everything else runs for real:
> - the engine, scheduling, gates and policy scans
> - impact analysis
> - the Maven build in the sandbox, which compiles and runs every test
> - rollbacks and the audit log
>
> Code content is **scripted** (selected from the playbook, not generated). To drive the checkpoints yourself, use `orch run` without `--auto-approve-as` and then `orch approve|reject|clarify|amend|stop`; see [SETUP.md](SETUP.md).

The numbers below come from the runs captured while building this submission (`orchestrator/runs/*/report.md`). Timings vary from machine to machine.

---

## 1. Greenfield: build the core service

**Input (well-defined):** "Build a URL shortener service. A client submits a long http(s) URL and receives a short link; visiting the short link redirects … optionally choose a custom alias, look up a link by its code, and list existing links. Invalid URLs must be rejected … duplicate alias must return a conflict."

**Decomposition**

- **intake:** feature `core-links`, matched on "short link", "redirect" and "custom alias", plus 5 acceptance criteria. No vague terms, so `clarify` is **skipped**.
- **plan:** 8 tasks covering 31 files:
  - `design:core-links`, then `implement:core-links` and `test:core-links` in parallel
  - `docs`
  - `verify:build`, `verify:policy` and `verify:contract`, which join before `release`
- **design:** 4 endpoints (`POST /api/v1/links`, `GET /api/v1/links/{code}`, `GET /api/v1/links`, `GET /r/{code}`) and migration `V1__create_short_links.sql`, so **change control is required**.

**Orchestration (actual event trace, condensed)**

```
approve-plan      [?] APPROVAL REQUESTED on requirement@1, plan@1  -> approved (DELEGATED)
change-control    [?] APPROVAL REQUESTED on design@1               -> approved (DELEGATED)
implement || write-tests || write-docs    (parallel)
  write-docs      llm-doc-writer [LLM] failed PERMANENT: no LLM provider configured
                  ~ fallback: llm-doc-writer -> template-doc-writer  -> succeeded
build-test || policy-scan || contract-check   (parallel)
  build-test      !! INJECTED FAULT (RETRYABLE): Maven repository read timed out
                  ~ retry: next attempt 2 in 2000 ms
                  agent maven-verifier [REAL] attempt 2
                  v BUILD SUCCESS: 31 tests, 0 failures, 0 errors, 0 skipped
release-readiness READY: 6/6 checks passed
approve-release   approved (DELEGATED) -> publish: release candidate manifest (32 files)
RUN COMPLETED
```

**Validation**

- A real `mvn clean verify` of the generated project passed: 31 tests.
- 0 policy findings across 7 rules.
- The contract check found 4 endpoints added and none removed.
- The release checklist passed 6/6.
- **Metrics:** 13 agent invocations, 1 retry, 1 fallback. MTTR covers 2 recovered incidents: the transient build failure and the LLM agent being unavailable. The audit chain is intact.

---

## 2. Brownfield: add click analytics to existing code

**Input:** "Add click analytics to the existing URL shortener: record every redirect and expose per-link statistics … Redirect latency and availability must not be affected … visitor privacy must be protected."

The sandbox is first seeded with the greenfield output (32 files).

**Codebase reasoning (`impact-analysis`, real static analysis of the seeded code)**

```
2 existing file(s) modified, 14 added; packages [link, web, analytics]; 1 data flow(s)
modifiedFiles: LinkResolvedEvent.java, RedirectController.java
dataFlows:     RedirectController -> LinkResolvedEvent -> ClickRecorder -> table click_events
regressionRisks:
  - Modifies existing RedirectController [RestController] serving [GET /r/{code}]
  - New consumer ClickRecorder runs on the publisher's thread ([RedirectController]):
    it must be non-blocking so redirect latency is unaffected
  - Schema change adds table(s) [click_events]: requires change-control approval
baseline: 4 endpoints, 20 classes, migrations [V1__create_short_links.sql], testMethods 24
```

**Decomposition:** `click-analytics`, with 8 tasks and 16 files. The design shows 2 endpoints in the changed files and migration V2, so change control is **required** (schema change plus 2 existing files modified).

**Orchestration: guardrail and rework**

The first implementation carries an **injected privacy defect**: the V2 migration adds a `client_ip` column. It is declared in the playbook and labelled `[INJECTED DEFECT]` in the `code-changes` artifact.

```
policy-scan   x POLICY_BLOCK: pii-raw-ip @ .../V2__create_click_events.sql:8
build-test    v BUILD SUCCESS: 47 tests (ran in parallel; the build alone cannot catch this)
<> REWORK: policy-scan failed -> re-run from implement (1/2),
   invalidating [implement, contract-check, build-test, policy-scan, release-readiness, approve-release, publish]
   <- rollback (invalidation) restored 12 file(s)
implement     v 12 file(s) applied ... remediating [pii-raw-ip]     (reads policy-feedback@1)
policy-scan   v 0 blocking, 0 warning finding(s) across 7 rules
build-test    v BUILD SUCCESS: 47 tests, 0 failures
release-readiness READY: 6/6 -> approve-release -> publish
```

`write-tests` and `write-docs` were **not** re-run, because the rework invalidates only the downstream closure of `implement`.

**Validation**

- `tests-not-decreased`: 24 test methods at baseline, more after the change.
- Contract: added `GET /api/v1/links/{code}/stats`, removed nothing.
- The final migration has no `client_ip` column.
- **Metrics:** 17 agent invocations, 1 rework, 1 rollback (12 files restored), 1 fallback.

---

## 3. Ambiguous: "secure and reliable links"

**Input:** "Links must be secure and reliable, and we need to know how they perform."

**Requirement understanding**

```
intake: "Unresolved request: ..."   features: []   vagueTerms: [secure, reliable, perform]
missing: ["no concrete capability could be identified from the text",
          "quality goals are stated without measurable thresholds"]
decision: Pause for human clarification before planning
[?] CLARIFICATION REQUESTED: 3 question(s)      -> RUN PAUSED (WAITING_CLARIFICATION)
```

The questions, with options drawn from the playbook:

| Term | Question | Options |
|---|---|---|
| secure | What does 'secure' mean for links? | `url-safety`, or `auth` (out of scope) |
| reliable | What does 'reliable' mean here? | `rate-limit`, or `no-change` |
| perform | Click analytics, or service latency/SLOs? | `existing-analytics`, or `slo-dashboards` (out of scope) |

The product owner's answers (scripted, delegated) are `secure=url-safety`, `reliable=no-change` and `perform=existing-analytics`. The requirement is normalized to v2 with one feature, `url-safety`, and its 4 acceptance criteria.

**Orchestration: conditional path and replanning**

```
plan            1 feature(s), 8 tasks, 3 files: [url-safety]
impact-analysis 0 existing file(s) modified, 3 added   (attaches via the TargetUrlPolicy extension point)
design          0 migrations, 0 new deps -> change-control SKIPPED (condition false)
build-test      BUILD SUCCESS: 70 tests ... release-readiness READY -> paused at approve-release

[*] requirement@3 amended by delegated:product-owner:
    'reliable' must include protection against link-creation floods.
<> REPLAN: requirement@3 changed;
   invalidated [plan, impact-analysis, approve-plan, design, change-control, implement, write-tests,
                write-docs, contract-check, build-test, policy-scan, release-readiness, approve-release, publish]
   preserved   [intake, clarify]
   approval voided at approve-release, approve-plan;  rollbacks restored 2 + 1 + 1 file(s)
plan            2 feature(s), 11 tasks, 7 files: [url-safety, rate-limit]
approve-plan    re-requested on requirement@3, plan@2, impact-report@2 -> approved
build-test      BUILD SUCCESS: 73 tests
approve-release (re-requested) -> publish -> RUN COMPLETED
```

**Validation**

- No planning happened on undefined terms.
- The out-of-scope options are recorded.
- The replan kept `intake` and `clarify` (1 execution each) and re-ran `plan` (2 executions).
- Approvals tied to stale inputs were voided and requested again.
- 73 tests pass.
- **Metrics:** 25 agent invocations, 1 replan, 3 rollbacks, 2 fallbacks.

---

## 4. Safe-stop: a human rejects the plan

```
approve-plan  [-] REJECTED by delegated:demo-reviewer: scope must be confirmed with security first
RUN SAFE-STOPPED [APPROVAL_REJECTED]           (no design, no code, empty sandbox)
orch resume -> approval voided -> APPROVAL REQUESTED again -> RUN PAUSED
```

Other safe-stop triggers are covered by tests in `OrchestratorTest`:

- kill switch (`orch stop`)
- execution budget exceeded
- approval timeout
- rework limit reached
- a stage failing terminally, with rollback, then a resume that recovers
- crash recovery of a stage that was mid-write
