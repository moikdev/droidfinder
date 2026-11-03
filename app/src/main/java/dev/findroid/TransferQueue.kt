package dev.findroid

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

enum class TransferState(val title: String) {
    QUEUED("Queued"),
    RUNNING("Running"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Needs attention"),
    CANCELLED("Cancelled"),
    INTERRUPTED("Interrupted — review destination"),
}

data class TransferItemResult(
    val source: String,
    val destination: String? = null,
    val error: String? = null,
    val skipped: Boolean = false,
)

data class TransferRetryTarget(val source: String, val parent: String)

data class TransferJob(
    val id: String = UUID.randomUUID().toString(),
    val paths: List<String>,
    val destination: String,
    val move: Boolean,
    val state: TransferState = TransferState.QUEUED,
    val results: List<TransferItemResult> = emptyList(),
    val bytes: Long = 0,
    val items: Int = 0,
    val current: String = "",
    val created: Long = System.currentTimeMillis(),
    val started: Long = 0,
    val finished: Long = 0,
    val inFlight: String? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val mergePlans: List<MergePlan> = emptyList(),
    val estimate: TransferEstimate? = null,
    val availableAtStart: Long? = null,
    val verifyCopies: Boolean = false,
    val verifications: List<CopyVerificationResult> = emptyList(),
) {
    val title
        get() =
            "${if(move) "Move" else "Copy"} ${paths.size} ${if(paths.size==1) "item" else "items"}"

    val successful
        get() = results.filter { it.destination != null && it.error == null }

    val remaining
        get() = paths.filter { path ->
            results.none { it.source == path && (it.destination != null || it.skipped) }
        }

    /** Published or ambiguous members are review-only, never replayed as whole folder roots. */
    val retryTargets: List<TransferRetryTarget>
        get() = buildList {
            remaining
                .filter { root -> mergePlans.none { it.source == root } && root != inFlight }
                .forEach { add(TransferRetryTarget(it, destination)) }
            mergePlans.forEach { plan ->
                plan.members
                    .filter {
                        it.destination == null &&
                            !it.skipped &&
                            it.source != inFlight &&
                            it.cleanup != SourceCleanup.FAILED &&
                            it.recovery == null
                    }
                    .forEach { add(TransferRetryTarget(it.source, it.parent)) }
            }
        }

    val terminal
        get() = state !in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)

    fun afterRestart() = if (!terminal) copy(state = TransferState.INTERRUPTED) else this
}

/** Atomic, versioned job journal. Pending work is never automatically replayed after restart. */
class TransferJournal(private val file: File) : TransferStore {
    private val backup = File(file.path + ".bak")
    private var loadedBackup = false

    @Synchronized
    override fun load(): List<TransferJob> {
        if (!file.exists() && !backup.exists()) return emptyList()
        return runCatching { read(file) }
            .getOrElse { original ->
                runCatching { read(backup).also { loadedBackup = true } }
                    .getOrElse {
                        throw java.io.IOException(
                            "Transfer history could not be read. Journal files were kept.",
                            original,
                        )
                    }
            }
    }

    private fun read(from: File): List<TransferJob> {
        require(from.length() <= 64L * 1024 * 1024) { "Transfer journal is too large." }
        val p = Properties().apply { from.inputStream().use(::load) }
        require(p.getProperty("version") == "1") { "Unsupported transfer journal." }
        return (0 until p.getProperty("count").toInt().also { require(it in 0..10_000) }).map { i ->
            fun get(key: String) = p.getProperty("$i.$key") ?: error("Missing job field: $key")
            fun count(key: String) =
                get(key).toInt().also { require(it in 0..100_000) { "Invalid job item count." } }
            val paths = (0 until count("paths")).map { get("path.$it") }
            require(paths.isNotEmpty())
            TransferJob(
                id = get("id"),
                paths = paths,
                destination = get("destination"),
                move = get("move").toBooleanStrict(),
                state = TransferState.valueOf(get("state")),
                bytes = get("bytes").toLong(),
                items = get("items").toInt(),
                current = get("current"),
                created = get("created").toLong(),
                started = get("started").toLong(),
                finished = get("finished").toLong(),
                inFlight = get("inFlight").ifBlank { null },
                conflictPolicy = ConflictPolicy.valueOf(p.getProperty("$i.conflictPolicy", "ASK")),
                verifyCopies = p.getProperty("$i.verifyCopies", "false").toBooleanStrict(),
                verifications = (0 until p.getProperty("$i.verifications", "0").toInt().also {
                    require(it in 0..MAX_COPY_VERIFICATION_RESULTS)
                }).map { n ->
                    CopyVerificationResult(
                        root = get("verification.$n.root"), source = get("verification.$n.source"),
                        status = CopyVerificationStatus.valueOf(get("verification.$n.status")),
                        destination = get("verification.$n.destination").ifBlank { null },
                        bytes = get("verification.$n.bytes").toLong(),
                        expectedSha256 = get("verification.$n.expected").ifBlank { null },
                        actualSha256 = get("verification.$n.actual").ifBlank { null },
                        error = get("verification.$n.error").ifBlank { null },
                    )
                },
                results =
                    (0 until count("results")).map { n ->
                        TransferItemResult(
                            get("result.$n.source"),
                            get("result.$n.destination").ifBlank { null },
                            get("result.$n.error").ifBlank { null },
                            p.getProperty("$i.result.$n.skipped", "false").toBooleanStrict(),
                        )
                    },
            ).also(::validateCopyVerification)
        }
    }

    @Synchronized
    override fun preserveDamaged(): List<File> {
        val suffix = ".damaged-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        return listOf(file, backup)
            .filter { it.exists() }
            .map { old ->
                File(old.path + suffix).also { Files.move(old.toPath(), it.toPath()) }
            }
            .also { loadedBackup = false }
    }

    @Synchronized
    override fun save(jobs: List<TransferJob>) {
        jobs.forEach(::validateCopyVerification)
        require(
            jobs.all {
                it.mergePlans.isEmpty() && it.estimate == null && it.availableAtStart == null
            },
        ) {
            "Transfer details require the database journal."
        }
        val p =
            Properties().apply {
                setProperty("version", "1")
                setProperty("count", jobs.size.toString())
            }
        jobs.forEachIndexed { i, job ->
            fun put(key: String, value: Any?) {
                p.setProperty("$i.$key", value?.toString().orEmpty())
            }
            put("id", job.id)
            put("destination", job.destination)
            put("move", job.move)
            put("state", job.state.name)
            put("bytes", job.bytes)
            put("items", job.items)
            put("current", job.current)
            put("created", job.created)
            put("started", job.started)
            put("finished", job.finished)
            put("inFlight", job.inFlight)
            put("conflictPolicy", job.conflictPolicy.name)
            put("verifyCopies", job.verifyCopies)
            put("verifications", job.verifications.size)
            job.verifications.forEachIndexed { n, result ->
                put("verification.$n.root", result.root)
                put("verification.$n.source", result.source)
                put("verification.$n.status", result.status.name)
                put("verification.$n.destination", result.destination)
                put("verification.$n.bytes", result.bytes)
                put("verification.$n.expected", result.expectedSha256)
                put("verification.$n.actual", result.actualSha256)
                put("verification.$n.error", result.error)
            }
            put("paths", job.paths.size)
            job.paths.forEachIndexed { n, path -> put("path.$n", path) }
            put("results", job.results.size)
            job.results.forEachIndexed { n, result ->
                put("result.$n.source", result.source)
                put("result.$n.destination", result.destination)
                put("result.$n.error", result.error)
                put("result.$n.skipped", result.skipped)
            }
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        try {
            temporary.outputStream().use { output ->
                p.store(output, "Findroid transfer journal")
                output.fd.sync()
            }
            if (file.exists() && !loadedBackup)
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            loadedBackup = false
        } finally {
            temporary.delete()
        }
    }
}

class TransferPublicationException(
    val destination: String,
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
