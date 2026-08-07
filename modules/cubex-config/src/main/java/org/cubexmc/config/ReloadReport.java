package org.cubexmc.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of one {@link ReloadChain} run: which stages ran, which were gated out or skipped after an
 * abort, and what failed.
 *
 * <p>A reload that silently half-applies is worse than one that fails loudly, so the chain reports
 * per-stage results rather than just throwing the first exception.
 */
public final class ReloadReport {

    private final List<String> succeeded;
    private final List<String> skipped;
    private final List<Failure> failures;

    ReloadReport(List<String> succeeded, List<String> skipped, List<Failure> failures) {
        this.succeeded = new ArrayList<>(succeeded);
        this.skipped = new ArrayList<>(skipped);
        this.failures = new ArrayList<>(failures);
    }

    /** True when no stage failed. Gated-out stages do not count as failures. */
    public boolean ok() {
        return failures.isEmpty();
    }

    public List<String> succeeded() {
        return Collections.unmodifiableList(succeeded);
    }

    /** Stages that did not run: gated out by {@code addIf}, or skipped after an ABORT. */
    public List<String> skipped() {
        return Collections.unmodifiableList(skipped);
    }

    public List<Failure> failures() {
        return Collections.unmodifiableList(failures);
    }

    /** One line per failure, ready for a log or an operator-facing message. */
    public List<String> failureSummaries() {
        List<String> summaries = new ArrayList<>();
        for (Failure failure : failures) {
            summaries.add(failure.summary());
        }
        return summaries;
    }

    /** A stage that threw, paired with the exception so callers can log a stack trace. */
    public static final class Failure {

        private final String stage;
        private final Exception cause;

        Failure(String stage, Exception cause) {
            this.stage = stage == null ? "" : stage;
            this.cause = cause;
        }

        public String stage() {
            return stage;
        }

        public Exception cause() {
            return cause;
        }

        public String summary() {
            String message = cause == null ? "unknown error" : String.valueOf(cause.getMessage());
            return stage + ": " + message;
        }
    }
}
