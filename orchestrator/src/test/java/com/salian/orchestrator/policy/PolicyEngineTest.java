package com.salian.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.salian.orchestrator.policy.PolicyConfig.Severity;
import com.salian.orchestrator.workspace.Workspace;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyEngineTest {

    @TempDir
    Path dir;

    private final PolicyEngine engine = new PolicyEngine(new PolicyConfig("t", List.of("url-shortener/"),
            List.of("org.springframework.boot:*", "com.h2database:h2"), 4, Map.of(
            PolicyEngine.PII_RAW_IP, Severity.BLOCK,
            PolicyEngine.SECRETS, Severity.BLOCK,
            PolicyEngine.PROTECTED_PATHS, Severity.BLOCK,
            PolicyEngine.MIGRATION_IMMUTABILITY, Severity.BLOCK,
            PolicyEngine.DEPENDENCY_ALLOWLIST, Severity.BLOCK,
            PolicyEngine.CHANGE_SIZE, Severity.WARN,
            PolicyEngine.TESTS_ACCOMPANY_CODE, Severity.WARN)));

    private Workspace workspace() {
        return new Workspace(dir.resolve("ws"), dir.resolve("journal"));
    }

    @Test
    void cleanChangePasses() {
        Workspace ws = workspace();
        ws.write("impl", "url-shortener/src/main/java/A.java", "class A { private String visitorHash; }");
        ws.write("tests", "url-shortener/src/test/java/ATest.java", "class ATest {}");
        ws.write("impl", "url-shortener/src/main/resources/application.yml",
                "password: ${SPRING_DATASOURCE_PASSWORD:postgres}\nlocal:\n  password: \"\"");

        PolicyEngine.Result result = engine.evaluate(ws);

        assertThat(result.blocking()).isZero();
        assertThat(result.warnings()).isZero();
        assertThat(result.evaluations()).hasSize(7);
    }

    @Test
    void blocksRawIpSecretsProtectedPathsMigrationEditsAndUnvettedDependencies() {
        Workspace ws = workspace();
        ws.seed("url-shortener/src/main/resources/db/migration/V1__init.sql", "create table t (id int);");
        ws.write("impl", "url-shortener/src/main/resources/db/migration/V1__init.sql", "create table t (id int, x int);");
        ws.write("impl", "url-shortener/src/main/resources/db/migration/V2__clicks.sql", "create table c (client_ip varchar(45));");
        ws.write("impl", "url-shortener/src/main/resources/application.yml", "api_key: \"abcdefghijklmnopqrstuvwx\"");
        ws.write("impl", "src/main/java/com/salian/productapi/Hack.java", "class Hack {}");
        ws.write("impl", "url-shortener/pom.xml",
                "<dependency>\n<groupId>com.evil</groupId>\n<artifactId>miner</artifactId>\n</dependency>");

        PolicyEngine.Result result = engine.evaluate(ws);

        assertThat(result.findings()).extracting(PolicyEngine.Finding::rule).contains(
                PolicyEngine.PII_RAW_IP, PolicyEngine.SECRETS, PolicyEngine.PROTECTED_PATHS,
                PolicyEngine.MIGRATION_IMMUTABILITY, PolicyEngine.DEPENDENCY_ALLOWLIST);
        assertThat(result.findings()).filteredOn(f -> f.rule().equals(PolicyEngine.PII_RAW_IP))
                .first().satisfies(f -> assertThat(f.line()).isEqualTo(1));
        assertThat(result.warnings()).isEqualTo(1); // change-size: 5 files > limit 4
    }
}
