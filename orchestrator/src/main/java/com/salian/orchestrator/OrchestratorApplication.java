package com.salian.orchestrator;

import com.salian.orchestrator.cli.Cli;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot shell for the orchestrator CLI ({@code java -jar orchestrator.jar <command> ...}). */
@SpringBootApplication
public class OrchestratorApplication implements CommandLineRunner, ExitCodeGenerator {

    private int exitCode;

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(OrchestratorApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        try {
            exitCode = new Cli(System.out).run(args);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            System.err.println("error: " + ex.getMessage());
            exitCode = 2;
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
