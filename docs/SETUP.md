# Setup

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 | everything |
| Maven | 3.9+ on `PATH` | building projects in the orchestrator's sandbox (`mvn` / `mvn.cmd`). The projects themselves also ship wrappers. |
| Docker or PostgreSQL 16 | optional | running the shortener against PostgreSQL. The `local` profile uses in-memory H2 instead. |

All commands below run from the repository root. On Windows use `mvnw.cmd` and backslashes; in Git Bash or on Linux/macOS use `./mvnw`.

## 1. Build and test

```bash
cd url-shortener && ./mvnw -B clean verify && cd ..
```

```bash
cd orchestrator && ./mvnw -B clean package && cd ..
```

```bash
./mvnw -B clean verify
```

The first command builds the shortener (73 tests), the second builds the orchestrator (33 tests) and produces `orchestrator/target/orchestrator.jar`, and the third is the untouched Product API (16 tests).

> **Windows + Git Bash:** with `core.autocrlf=true` the `mvnw` scripts are checked out with CRLF line endings and fail under bash. Use `mvnw.cmd` from PowerShell or cmd, or `mvn` directly.

## 2. Run the URL shortener

Without a database server (in-memory H2, same Flyway migrations):

```bash
java -jar url-shortener/target/url-shortener-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Against PostgreSQL:

```bash
createdb shortenerdb
```

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/shortenerdb java -jar url-shortener/target/url-shortener-0.1.0-SNAPSHOT.jar
```

Try it:

```bash
curl -i -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' -d '{"targetUrl":"https://example.com/docs","customAlias":"docs"}'
```

```bash
curl -i localhost:8080/r/docs
```

```bash
curl localhost:8080/api/v1/links/docs/stats
```

Swagger UI is at http://localhost:8080/swagger-ui.html and health at http://localhost:8080/actuator/health.

## 3. Run the scenario demos

```bash
java -jar orchestrator/target/orchestrator.jar demo all --offline
```

`--offline` passes `-o` to the sandbox Maven builds. Use it once the dependencies are in your local repository (for example after step 1); drop it on a fresh machine. `demo all` runs greenfield, brownfield, ambiguous and safe-stop, which takes about 3–4 minutes because each scenario runs real Maven builds. Each run writes these files to `orchestrator/runs/<runId>/`:

| File | Content |
|---|---|
| `state.json` | full persisted run state: stages, versioned artifacts with lineage, approvals, decisions |
| `audit.jsonl` | hash-chained audit trail |
| `report.md`, `metrics.json` | reviewer report and reliability metrics |
| `release-summary.md` | release-readiness checklist shown at `approve-release` |
| `workspace/url-shortener/` | the sandbox project the agents built (including generated `docs/*-change-notes.md`) |
| `logs/build-*.log` | real Maven output for every build attempt |

`orchestrator/runs/metrics-summary.json` aggregates metrics across all runs.

## 4. Drive a run yourself (interactive human controls)

`orch` below stands for `java -jar orchestrator/target/orchestrator.jar`; `orchestrator/orch.cmd` and `orchestrator/orch.sh` are launchers for it.

```bash
orch run ambiguous --run-id amb-1 --offline
```

The run pauses with `WAITING_CLARIFICATION`. Read the questions, then write your own answers file:

```bash
orch artifact amb-1 ambiguity-report
```

```bash
echo '{"secure":"url-safety","reliable":"rate-limit","perform":"existing-analytics"}' > answers.json
```

Submit the answers, then resume. The run pauses again at `approve-plan`:

```bash
orch clarify amb-1 clarify answers.json --by alice
```

```bash
orch resume amb-1
```

Approve or reject at each checkpoint, then resume. Your decisions are recorded as `human:alice` (INTERACTIVE):

```bash
orch approve amb-1 approve-plan --by alice --comment "scope ok"
```

```bash
orch resume amb-1
```

Other controls:

```bash
orch amend amb-1 requirement new-requirement.json --by alice --reason "scope change"
```

```bash
orch stop amb-1 --by alice --reason "pause everything"
```

```bash
orch status amb-1
```

```bash
orch report amb-1
```

```bash
orch lineage amb-1 release-summary
```

```bash
orch verify-audit amb-1
```

```bash
orch metrics
```

`amend` triggers replanning, and the next `resume` re-executes only the affected stages. `stop` is the kill switch: the run safe-stops at the next checkpoint and can be resumed later.

`orch run <scenario> --auto-approve-as ci-bot` runs without pausing, with approvals recorded as delegated, which suits unattended pipelines. The bundled CI workflow runs `demo all` instead, whose scripted reviewer is also recorded as delegated.

## 5. Where things live

```
url-shortener/            service source, tests, README (API reference)
orchestrator/
  workflows/sdlc.yaml     the dependency graph
  policies/policy.yaml    guardrails
  playbooks/<scenario>/   requirement text, feature catalog, ambiguity options,
                          scripted code overlays, injected faults/defects
  src/main/java/...       engine, agents, policy, audit, metrics, CLI
docs/                     architecture, scenarios, setup, testing, final summary
```
