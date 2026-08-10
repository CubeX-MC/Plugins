package org.cubexmc.metro.persistence

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Coordinates persistent YAML saves so newer snapshots cannot be overwritten by
 * older async writes.
 */
class SaveCoordinator(
    private val logger: Logger,
    private val executor: Executor,
) {
    private val latestVersions: MutableMap<Path, AtomicLong> = ConcurrentHashMap()
    private val writeChains: MutableMap<Path, CompletableFuture<Void>> = ConcurrentHashMap()

    init {
        Objects.requireNonNull(logger, "logger")
        Objects.requireNonNull(executor, "executor")
    }

    fun submit(targetFile: Path, snapshotSupplier: Supplier<String>): Long {
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier")
        val snapshot = snapshotSupplier.get()
        return submitSnapshot(targetFile, snapshot)
    }

    fun submitSnapshot(targetFile: Path, snapshot: String): Long {
        val normalizedTarget = normalize(targetFile)
        val version = nextVersion(normalizedTarget)

        writeChains.compute(normalizedTarget) { path, previous ->
            val base = previous ?: CompletableFuture.completedFuture(null)
            val next = base.handle { _, _ -> null }
                .thenRunAsync({ writeIfCurrent(path, version, snapshot) }, executor)
            next.whenComplete { _, error ->
                if (error != null) {
                    logger.log(Level.SEVERE, "Failed to save $path at version $version", error)
                }
                writeChains.remove(path, next)
            }
            next
        }

        return version
    }

    fun saveNow(targetFile: Path, snapshot: String) {
        val normalizedTarget = normalize(targetFile)
        flush(normalizedTarget)
        val version = nextVersion(normalizedTarget)
        writeSnapshot(normalizedTarget, version, snapshot)
    }

    fun flush(targetFile: Path) {
        val normalizedTarget = normalize(targetFile)
        val pending = writeChains[normalizedTarget] ?: return
        pending.join()
    }

    fun flushAll() {
        for (pending in ArrayList(writeChains.values)) {
            pending.join()
        }
    }

    fun latestVersion(targetFile: Path): Long {
        val version = latestVersions[normalize(targetFile)]
        return version?.get() ?: 0L
    }

    private fun nextVersion(targetFile: Path): Long =
        latestVersions.computeIfAbsent(targetFile) { AtomicLong() }.incrementAndGet()

    private fun writeIfCurrent(targetFile: Path, version: Long, snapshot: String) {
        if (!isCurrent(targetFile, version)) {
            logger.fine("Skipping stale save for $targetFile at version $version")
            return
        }
        writeSnapshot(targetFile, version, snapshot)
    }

    private fun writeSnapshot(targetFile: Path, version: Long, snapshot: String) {
        var lastError: IOException? = null
        for (attempt in 1..MAX_WRITE_ATTEMPTS) {
            try {
                writeAtomically(targetFile, version, snapshot)
                return
            } catch (ex: IOException) {
                lastError = ex
                logger.log(Level.WARNING, "Failed to save $targetFile at version $version (attempt $attempt/$MAX_WRITE_ATTEMPTS)", ex)
            }
        }

        if (lastError != null) {
            logger.log(Level.SEVERE, "Giving up saving $targetFile at version $version", lastError)
        }
    }

    private fun writeAtomically(targetFile: Path, version: Long, snapshot: String) {
        val parent = targetFile.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        val tempFile = targetFile.resolveSibling(targetFile.fileName.toString() + ".tmp-" + version)
        try {
            Files.writeString(tempFile, snapshot)
            if (!isCurrent(targetFile, version)) {
                Files.deleteIfExists(tempFile)
                logger.fine("Discarded stale save for $targetFile at version $version")
                return
            }

            try {
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                logger.fine("Atomic move is not supported for $targetFile; falling back to replace existing.")
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun isCurrent(targetFile: Path, version: Long): Boolean = latestVersion(targetFile) == version

    private fun normalize(targetFile: Path): Path = Objects.requireNonNull(targetFile, "targetFile").toAbsolutePath().normalize()

    companion object {
        private const val MAX_WRITE_ATTEMPTS = 2
    }
}
