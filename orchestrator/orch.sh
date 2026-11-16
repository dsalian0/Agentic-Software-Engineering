#!/usr/bin/env sh
# Orchestrator CLI launcher (Linux/macOS/Git Bash). Build once with: ./orchestrator/mvnw -f orchestrator/pom.xml -B package
exec java -jar "$(dirname "$0")/target/orchestrator.jar" "$@"
