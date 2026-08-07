package org.cubexmc.config;

/**
 * What a {@link ReloadChain} does when a stage throws.
 *
 * <p>Mirrors {@link MigrationFailurePolicy}, but the choice matters more here: a reload runs against
 * a live server, so "stop at the first failure" can leave the plugin half-reloaded.
 */
public enum ReloadFailurePolicy {

    /** Stop at the first failing stage. Later stages are recorded as skipped. */
    ABORT,

    /** Record the failure and keep going, so one bad stage cannot strand the rest. */
    CONTINUE
}
