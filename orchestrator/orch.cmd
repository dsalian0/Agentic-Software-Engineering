@echo off
rem Orchestrator CLI launcher (Windows). Build once with: orchestrator\mvnw.cmd -f orchestrator\pom.xml -B package
java -jar "%~dp0target\orchestrator.jar" %*
