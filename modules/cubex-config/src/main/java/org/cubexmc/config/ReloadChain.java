package org.cubexmc.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.cubexmc.core.Reloadable;

/**
 * An ordered list of named reload stages.
 *
 * <p>Reload runs against a live server, so the chain is built around the two things a hand-rolled
 * reload method always ends up needing:
 *
 * <ul>
 *   <li>{@link #addIf} for stages that must not run when an earlier stage failed — the classic case
 *       is "do not reload state from disk, because flushing the in-memory state just failed and the
 *       file is stale";</li>
 *   <li>{@link #run()} returning a {@link ReloadReport} naming the stage that broke, instead of
 *       throwing the first exception and leaving the operator to guess.</li>
 * </ul>
 *
 * <p>The default policy is {@link ReloadFailurePolicy#CONTINUE} so one bad stage cannot strand the
 * rest; use {@link ReloadFailurePolicy#ABORT} when later stages are meaningless after a failure.
 */
public final class ReloadChain implements Reloadable {

    private final List<Entry> entries = new ArrayList<>();
    private ReloadFailurePolicy failurePolicy = ReloadFailurePolicy.CONTINUE;

    private ReloadChain() {
    }

    public static ReloadChain create() {
        return new ReloadChain();
    }

    public ReloadChain failurePolicy(ReloadFailurePolicy policy) {
        this.failurePolicy = policy == null ? ReloadFailurePolicy.CONTINUE : policy;
        return this;
    }

    /**
     * There is deliberately no {@code Runnable} overload. {@link Reloadable} is also a single-method
     * interface, so the two would be indistinguishable to a Kotlin lambda and every call site would
     * need a cast. {@code Reloadable} is the wider of the pair — it may throw — so it is the one
     * that stays.
     */
    public ReloadChain add(String name, Reloadable reloadable) {
        if (reloadable != null) {
            entries.add(new Entry(name, reloadable, null));
        }
        return this;
    }

    /**
     * Adds a stage that only runs when {@code gate} returns true at run time. A gated-out stage is
     * reported as skipped, not as a failure.
     *
     * <p>The gate is evaluated when the chain runs, not when it is built, so it can observe the
     * result of earlier stages.
     */
    public ReloadChain addIf(String name, BooleanSupplier gate, Reloadable reloadable) {
        if (reloadable != null) {
            entries.add(new Entry(name, reloadable, gate));
        }
        return this;
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) {
            names.add(entry.name);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Runs every stage and reports the result. Never throws; inspect the report instead.
     */
    public ReloadReport run() {
        List<String> succeeded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ReloadReport.Failure> failures = new ArrayList<>();
        boolean aborted = false;

        for (Entry entry : entries) {
            if (aborted) {
                skipped.add(entry.name);
                continue;
            }
            if (entry.gate != null && !entry.gate.getAsBoolean()) {
                skipped.add(entry.name);
                continue;
            }
            try {
                entry.reloadable.reload();
                succeeded.add(entry.name);
            } catch (Exception ex) {
                failures.add(new ReloadReport.Failure(entry.name, ex));
                if (failurePolicy == ReloadFailurePolicy.ABORT) {
                    aborted = true;
                }
            }
        }
        return new ReloadReport(succeeded, skipped, failures);
    }

    /**
     * {@link Reloadable} form, so a chain can be nested inside another chain. Delegates to
     * {@link #run()} and throws the first failure so the outer chain can record it.
     */
    @Override
    public void reload() throws Exception {
        ReloadReport report = run();
        if (!report.ok()) {
            ReloadReport.Failure first = report.failures().get(0);
            Exception cause = first.cause();
            throw cause == null ? new IllegalStateException(first.summary()) : cause;
        }
    }

    private static final class Entry {
        private final String name;
        private final Reloadable reloadable;
        private final BooleanSupplier gate;

        private Entry(String name, Reloadable reloadable, BooleanSupplier gate) {
            this.name = name == null ? "" : name;
            this.reloadable = reloadable;
            this.gate = gate;
        }
    }
}
