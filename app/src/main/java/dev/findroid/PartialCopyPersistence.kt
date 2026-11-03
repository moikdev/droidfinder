package dev.findroid

import java.io.File
import java.util.UUID
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Decimal strings preserve native 64-bit identities through JSON tooling without rounding. */
internal fun encodePartialCopy(record: PartialCopyRecord): String {
    validatePartialCopyRecord(record)
    fun stamp(value: PartialFileStamp) = JsonObject().apply {
        addProperty("device", value.device.toString()); addProperty("inode", value.inode.toString()); addProperty("size", value.size.toString())
        addProperty("modifiedSeconds", value.modifiedSeconds.toString()); addProperty("modifiedNanos", value.modifiedNanos.toString())
        addProperty("changedSeconds", value.changedSeconds.toString()); addProperty("changedNanos", value.changedNanos.toString())
        addProperty("regular", value.regular); addProperty("directory", value.directory); addProperty("links", value.links.toString())
    }
    return JsonObject().apply {
        addProperty("schema", 1); addProperty("owner", record.owner); addProperty("source", record.source); addProperty("target", record.target); addProperty("staging", record.staging)
        add("sourceStamp", stamp(record.sourceStamp)); add("destinationParentStamp", stamp(record.destinationParentStamp))
        add("stagingParentStamp", stamp(record.stagingParentStamp)); add("stagingStamp", stamp(record.stagingStamp))
        addProperty("offset", record.offset.toString()); addProperty("prefixSha256", record.prefixSha256)
        addProperty("verifyCopies", record.verifyCopies); addProperty("publishing", record.publishing)
        addProperty("resolvedSource", record.resolvedSource); addProperty("resolvedDestinationParent", record.resolvedDestinationParent)
        addProperty("resolvedPrivateRoot", record.resolvedPrivateRoot)
    }.toString().also { require(it.length <= 32 * 1024) { "Saved-copy record is too large." } }
}

internal fun decodePartialCopy(value: String): PartialCopyRecord {
    require(value.length <= 32 * 1024) { "Saved-copy record is too large." }
    val root = JsonParser.parseString(value).asJsonObject
    require(root.get("schema")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1" } == true) { "Unsupported saved-copy record. Files were kept for review." }
    fun JsonObject.text(key: String): String {
        val raw = get(key)
        require(raw != null && raw.isJsonPrimitive && raw.asJsonPrimitive.isString) { "Invalid saved-copy text." }
        return raw.asString
    }
    fun JsonObject.number(key: String): Long {
        val raw = text(key)
        require(raw.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Invalid saved-copy number." }
        return raw.toLong()
    }
    fun JsonObject.flag(key: String): Boolean {
        val raw = get(key)
        require(raw != null && raw.isJsonPrimitive && raw.asJsonPrimitive.isBoolean) { "Invalid saved-copy flag." }
        return raw.asBoolean
    }
    fun stamp(key: String): PartialFileStamp = root.getAsJsonObject(key).run {
        PartialFileStamp(number("device"), number("inode"), number("size"), number("modifiedSeconds"), number("modifiedNanos"),
            number("changedSeconds"), number("changedNanos"), flag("regular"), flag("directory"), number("links"))
    }
    return PartialCopyRecord(root.text("owner"), root.text("source"), root.text("target"), root.text("staging"),
        stamp("sourceStamp"), stamp("destinationParentStamp"), stamp("stagingParentStamp"), stamp("stagingStamp"),
        root.number("offset"), root.text("prefixSha256"), root.flag("verifyCopies"), root.flag("publishing"),
        root.text("resolvedSource"), root.text("resolvedDestinationParent"), root.text("resolvedPrivateRoot"))
        .also(::validatePartialCopyRecord)
}

internal fun validatePartialCopyRecord(record: PartialCopyRecord) {
    require(runCatching { UUID.fromString(record.owner).toString() == record.owner }.getOrDefault(false)) { "Invalid saved-copy owner." }
    require(listOf(record.source, record.target, record.staging, record.resolvedSource, record.resolvedDestinationParent, record.resolvedPrivateRoot).all {
        it.length in 1..4096 && '\u0000' !in it && File(it).isAbsolute && File(it).toPath().normalize().toString() == it
    }) { "Invalid saved-copy location." }
    require(File(record.staging).name == "data" && File(record.staging).parentFile?.name == record.owner &&
        record.source != record.target && record.staging != record.source && record.staging != record.target) { "Invalid saved-copy ownership." }
    require(listOf(record.sourceStamp, record.destinationParentStamp, record.stagingParentStamp, record.stagingStamp).all {
        it.device >= 0 && it.inode > 0 && it.size >= 0 && it.links > 0 && it.regular != it.directory &&
            it.modifiedNanos in 0..999_999_999 && it.changedNanos in 0..999_999_999
    }) { "Invalid saved-copy identity." }
    require(record.sourceStamp.regular && record.stagingStamp.regular && record.stagingStamp.links == 1L &&
        record.destinationParentStamp.directory && record.stagingParentStamp.directory &&
        record.offset in 0..record.sourceStamp.size && record.stagingStamp.size == record.offset &&
        record.prefixSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid saved-copy checkpoint." }
}

internal fun validatePartialCopyJob(job: TransferJob) {
    require(job.reusedBytes >= 0 && (!job.move || job.reusedBytes == 0L) && (job.partialCopyNotice?.length ?: 0) <= 1024) { "Invalid saved-copy history." }
    job.partialCopy?.let { record ->
        validatePartialCopyRecord(record)
        require(!job.move && record.source in job.paths && record.verifyCopies == job.verifyCopies &&
            File(record.target).parent == job.destination) { "Saved copy does not belong to this transfer." }
    }
}

internal fun validatePartialCopyJobs(jobs: List<TransferJob>) {
    jobs.forEach(::validatePartialCopyJob)
    val owners = jobs.mapNotNull { it.partialCopy?.owner }
    require(owners.distinct().size == owners.size) { "Two transfers cannot own the same saved copy." }
}
