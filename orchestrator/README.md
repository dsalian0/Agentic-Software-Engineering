# Orchestrator

A governed, stateful engine that runs software-development work as an explicit dependency graph:

- entry and exit gates
- parallel fan-out and joins
- human approval and clarification checkpoints
- bounded retries, fallback, rollback, rework and safe-stop
- policy guardrails
- dynamic replanning
- a hash-chained audit log and reliability metrics

| Read | For |
|---|---|
| [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) | How it works |
| [../docs/SCENARIOS.md](../docs/SCENARIOS.md) | The three demos |
| [../docs/SETUP.md](../docs/SETUP.md) | Commands |

Build it:

```bash
./mvnw -B clean package
```

Run all demos:

```bash
java -jar target/orchestrator.jar demo all --offline
```

Drive a run yourself:

```bash
java -jar target/orchestrator.jar run greenfield --run-id gf-1
```

```bash
java -jar target/orchestrator.jar approve gf-1 approve-plan --by <you>
```

```bash
java -jar target/orchestrator.jar resume gf-1
```

The CLI must be run from inside the repository, or pass `--repo <path>`. Run output goes to `runs/` (gitignored).

**Honesty labels.** Every agent invocation records its mode:

- **REAL:** computed at run time.
- **SCRIPTED:** code selected from a playbook, not generated.
- **LLM:** placeholder that is unavailable in this build.

Scripted human actions are recorded as `delegated:<name>`, never as a person.
