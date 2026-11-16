# Agentic Software Engineering System: URL Shortener

Documentation index for the interview assignment.

| Document | Contents |
|---|---|
| [FINAL_SUMMARY.md](FINAL_SUMMARY.md) | Start here: what was built, how it maps to the assignment, what is real and what is simulated, risks, assumptions |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components, orchestration model (dependency graph), control flow, governance controls, key decisions |
| [SCENARIOS.md](SCENARIOS.md) | Greenfield, brownfield, ambiguous (and safe-stop): decomposition, orchestration trace, validation |
| [SETUP.md](SETUP.md) | Build, run the service, run the demos, drive a run with the human controls |
| [TESTING.md](TESTING.md) | Test inventory, validation inside the workflow, limitations and trade-offs |
| [../url-shortener/README.md](../url-shortener/README.md) | Service API reference and design notes |
| [../orchestrator/README.md](../orchestrator/README.md) | Orchestrator quick reference |

Quick start (from the repository root, with JDK 21 and Maven installed):

```bash
cd orchestrator && ./mvnw -B clean package && cd ..
```

```bash
java -jar orchestrator/target/orchestrator.jar demo all
```

The first command builds the orchestrator and runs its 33 tests. The second runs all scenarios; each one builds and tests the shortener in a sandbox.
