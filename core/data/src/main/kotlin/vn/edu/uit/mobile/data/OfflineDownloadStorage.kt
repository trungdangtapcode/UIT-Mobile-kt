package vn.edu.uit.mobile.data

import android.content.Context
import android.net.*
import androidx.work.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

internal fun materialDestination(root: File, course: Course, file: CourseFile): File {
    require(course.id > 0 && course.accountId.isNotBlank())
    val original = file.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeIf { it.isNotBlank() && it !in setOf(".", "..") } ?: "resource"
    val suffix = original.substringAfterLast('.', "").takeIf { it.length in 1..16 && it.all(Char::isLetterOrDigit) }?.let { ".$it" }.orEmpty()
    val name = if (original.toByteArray().size <= 200) {
        original
    } else {
        buildString {
            var bytes = 0
            original.removeSuffix(suffix).codePoints().forEach { point ->
                val text = String(Character.toChars(point))
                val size = text.toByteArray().size
                if (bytes + size <= 200 - suffix.toByteArray().size) {
                    append(text)
                    bytes += size
                }
            }
            append(suffix)
        }
    }
    val urlKey = MessageDigest.getInstance("SHA-256").digest(file.url.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    return File(root, "${accountFolder(course.accountId)}/${course.id}/$urlKey-$name")
}
internal suspend fun verifiedMaterial(file: File): DownloadedFile? {
    val checksum = File(file.path + ".sha256")
    if (!file.isFile || !checksum.isFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            currentCoroutineContext().ensureActive()
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    return if (checksum.readText().trim() == actual) DownloadedFile(file.path, actual) else null
}
internal fun writeMaterialChecksum(file: File, checksum: String) {
    val target = File(file.path + ".sha256")
    val temporary = File(file.parentFile, ".checksum-" + UUID.randomUUID())
    try {
        temporary.writeText(checksum)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
fun createOfflineDownloadStorage(context: Context): OfflineDownloadStorage = PrivateOfflineDownloadStorage(File(context.applicationContext.filesDir, "materials"), StudyPrivateStorage(context, "offline_downloads", "uit.offline.downloads.v1"))
fun createFreeTimeBlockStorage(context: Context): FreeTimeBlockStorage = PrivateFreeTimeBlockStorage(StudyPrivateStorage(context, "free_time_blocks", "uit.freetime.blocks.v1"))
fun createOfflineCourseSourceLoader(storage: OfflineDownloadStorage): OfflineCourseSourceLoader = OfflineCourseSourceLoader { course, sections, assignments ->
    withContext(Dispatchers.IO) {
        val files = buildList {
            assignments.filter { it.accountId == course.accountId && it.courseId == course.id }.forEach { addAll(it.files) }
            sections.flatMap { it.modules }.filter { it.courseId == course.id }.forEach { addAll(it.files) }
        }.filter { it.name.isNotBlank() && it.url.isNotBlank() }.distinctBy { it.url }
        files.mapNotNull { file ->
            currentCoroutineContext().ensureActive()
            val verified = storage.cached(course, file) ?: return@mapNotNull null
            val local = File(verified.path)
            if (!local.isFile || local.length() <= 0L || local.length() > 8L * 1024 * 1024) return@mapNotNull null
            val text = runCatching { extractOfflineDocumentText(file.name, local.readBytes()) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            StudySource("file:${offlineDownloadId(course, file).take(32)}", file.name.take(200), text)
        }
    }
}

internal fun extractOfflineDocumentText(fileName: String, bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext == "ipynb") return extractNotebookText(bytes)
    val isPdf = ext == "pdf" || (bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-")
    val raw = when {
        isPdf -> extractPdfText(bytes)
        ext in setOf("txt", "md", "markdown", "csv", "json", "xml") -> bytes.toString(Charsets.UTF_8)
        ext in setOf("html", "htm") -> bytes.toString(Charsets.UTF_8).replace(Regex("<[^>]+>"), " ")
        else -> return null
    }
    return raw.replace(Regex("[\\s\\p{Z}\\u0000-\\u001f]+"), " ").trim().take(12000).trim().takeIf { it.isNotBlank() }
}

internal fun extractPdfText(bytes: ByteArray): String {
    val ascii = bytes.toString(Charsets.ISO_8859_1)
    val streamRegex = Regex("stream(?:\\r\\n|\\n|\\r)([\\s\\S]*?)(?:\\r\\n|\\n|\\r)?endstream")
    val chunks = mutableListOf<String>()
    for (match in streamRegex.findAll(ascii)) {
        val body = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
        val decoded = inflatePdfStream(body, false) ?: inflatePdfStream(body, true) ?: body.toString(Charsets.ISO_8859_1)
        extractPdfTextOperators(decoded).takeIf { it.isNotBlank() }?.let(chunks::add)
    }
    if (chunks.isEmpty()) {
        extractPdfTextOperators(ascii).takeIf { it.isNotBlank() }?.let(chunks::add)
    }
    return chunks.joinToString(" ")
}

private fun inflatePdfStream(input: ByteArray, nowrap: Boolean): String? = runCatching {
    val inflater = java.util.zip.Inflater(nowrap)
    try {
        inflater.setInput(input)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished() && out.size() < 1024 * 1024) {
            val count = inflater.inflate(buffer)
            if (count <= 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buffer, 0, count)
            }
        }
        out.toByteArray().takeIf { it.isNotEmpty() }?.toString(Charsets.ISO_8859_1)
    } finally {
        inflater.end()
    }
}.getOrNull()

private fun extractPdfTextOperators(stream: String): String {
    val btRegex = Regex("BT\\b([\\s\\S]*?)\\bET")
    val regions = btRegex.findAll(stream).map { it.groupValues[1] }.toList().ifEmpty { listOf(stream) }
    val tokenRegex = Regex("\\((?:\\\\.|[^\\\\()])*\\)|<([0-9A-Fa-f\\s]{2,})>")
    val out = StringBuilder()
    for (region in regions) {
        for (match in tokenRegex.findAll(region)) {
            val value = match.value
            val piece = if (value.startsWith("(") && value.endsWith(")")) {
                unescapePdfLiteral(value.substring(1, value.length - 1))
            } else {
                decodePdfHex(match.groupValues[1])
            }
            if (piece.isNotBlank()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(piece)
                if (out.length >= 24000) return out.toString()
            }
        }
    }
    return out.toString()
}

private fun unescapePdfLiteral(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            val next = raw[i + 1]
            when {
                next == 'n' -> {
                    sb.append('\n')
                    i += 2
                }
                next == 'r' -> {
                    sb.append('\r')
                    i += 2
                }
                next == 't' -> {
                    sb.append('\t')
                    i += 2
                }
                next in '0'..'7' -> {
                    var oct = "$next"
                    var j = i + 2
                    while (j < raw.length && oct.length < 3 && raw[j] in '0'..'7') {
                        oct += raw[j]
                        j++
                    }
                    sb.append(oct.toInt(8).toChar())
                    i = j
                }
                else -> {
                    sb.append(next)
                    i += 2
                }
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun decodePdfHex(hex: String): String {
    val clean = hex.filterNot(Char::isWhitespace)
    if (clean.length < 2) return ""
    val padded = if (clean.length % 2 == 1) "${clean}0" else clean
    val bytes = ByteArray(padded.length / 2) { idx -> padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
    return if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    } else {
        bytes.toString(Charsets.ISO_8859_1)
    }
}

class PrivateFreeTimeBlockStorage(private val storage: StudyStorage) : FreeTimeBlockStorage {
    private fun keyFor(accountIds: Set<String>): String {
        val canonical = accountIds.filter(String::isNotBlank).sorted().joinToString("\n")
        val suffix = if (canonical.isEmpty()) "local" else MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "free-time-blocks-v1:$suffix"
    }
    override fun load(accountIds: Set<String>): List<PersonalTimeBlock> = runCatching {
        elements(accountIds)?.take(10)?.mapNotNull { runCatching { block(it) }.getOrNull() }?.distinctBy { it.id }.orEmpty()
    }.getOrDefault(emptyList())
    private fun elements(accountIds: Set<String>): JsonArray? {
        val raw = storage.read(keyFor(accountIds)) ?: return null
        require(raw.length <= 64_000)
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        return root.getValue("blocks").jsonArray
    }
    override fun loadStrict(accountIds: Set<String>): List<PersonalTimeBlock> {
        val values = elements(accountIds) ?: return emptyList()
        require(values.size <= 10)
        return values.map(::block).also { require(it.map { block -> block.id }.distinct().size == it.size) }
    }
    private fun block(element: JsonElement): PersonalTimeBlock {
        val obj = element.jsonObject
        val id = obj.getValue("id").jsonPrimitive.content.trim()
        val title = obj.getValue("title").jsonPrimitive.content.trim()
        val start = obj.getValue("start").jsonPrimitive.long
        val end = obj.getValue("end").jsonPrimitive.long
        require(id.length in 1..128 && title.length in 1..80 && start < end)
        return PersonalTimeBlock(id, title, start, end)
    }

    override fun save(accountIds: Set<String>, blocks: List<PersonalTimeBlock>) {
        val key = keyFor(accountIds)
        val valid = blocks.filter { it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 80 && it.start < it.end }.distinctBy { it.id }.take(10)
        if (valid.isEmpty()) {
            storage.remove(key)
            return
        }
        val payload = buildJsonObject {
            put("version", 1)
            put(
                "blocks",
                JsonArray(
                    valid.map { block ->
                        buildJsonObject {
                            put("id", block.id)
                            put("title", block.title)
                            put("start", block.start)
                            put("end", block.end)
                        }
                    }
                )
            )
        }
        storage.write(key, payload.toString())
    }

    override fun clear(accountIds: Set<String>) {
        storage.remove(keyFor(accountIds))
    }
}

class PrivateOfflineDownloadStorage(private val root: File, private val journal: StudyStorage) : OfflineDownloadStorage {
    override suspend fun cached(course: Course, file: CourseFile): VerifiedOfflineFile? = withContext(Dispatchers.IO) {
        try {
            verifiedMaterial(materialDestination(root, course, file))?.let { VerifiedOfflineFile(it.path, File(it.path).length()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    override suspend fun remove(course: Course, file: CourseFile) = withContext(Dispatchers.IO) {
        val path = materialDestination(root, course, file)
        check(!path.exists() || path.delete()) { "Unable to remove file." }
        val checksum = File(path.path + ".sha256")
        check(!checksum.exists() || checksum.delete()) { "Unable to remove checksum." }
    }
    override suspend fun usedBytes(accountIds: Set<String>): Long = withContext(Dispatchers.IO) {
        accountIds.sumOf { id -> File(root, accountFolder(id)).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
    override suspend fun load(): OfflineDownloadJournal = withContext(Dispatchers.IO) {
        val raw = journal.read("offline-downloads-v1") ?: return@withContext OfflineDownloadJournal()
        require(raw.length <= 4_000_000) { "Saved download list is too large." }
        val data = Json.parseToJsonElement(raw).jsonObject
        require(data.getValue("version").jsonPrimitive.int == 1)
        val items = data.getValue("items").jsonArray.take(500).mapNotNull { value ->
            runCatching {
                val item = value.jsonObject
                val course = Course(item.getValue("courseId").jsonPrimitive.long, item.getValue("accountId").jsonPrimitive.content, item.getValue("code").jsonPrimitive.content, item.getValue("courseName").jsonPrimitive.content)
                val file = CourseFile(item.getValue("name").jsonPrimitive.content, item.getValue("url").jsonPrimitive.content, item.getValue("size").jsonPrimitive.long)
                require(course.id > 0 && course.accountId.length in 1..256 && file.name.length in 1..512 && file.url.length in 1..8192)
                OfflineDownloadItem(OfflineDownloadSpec(course, item.getValue("section").jsonPrimitive.content, item.getValue("sectionName").jsonPrimitive.content, file), OfflineDownloadStatus.valueOf(item.getValue("status").jsonPrimitive.content))
            }.getOrNull()
        }.distinctBy { it.id }
        OfflineDownloadJournal(items, data.getValue("wifiOnly").jsonPrimitive.boolean)
    }
    override suspend fun save(journal: OfflineDownloadJournal) = withContext(Dispatchers.IO) {
        val data = buildJsonObject {
            put("version", 1)
            put("wifiOnly", journal.wifiOnly)
            put(
                "items",
                JsonArray(
                    journal.items.take(500).map { item ->
                        buildJsonObject {
                            put("accountId", item.spec.course.accountId)
                            put("courseId", item.spec.course.id)
                            put("code", item.spec.course.shortname)
                            put("courseName", item.spec.course.fullname)
                            put("name", item.spec.file.name)
                            put("url", item.spec.file.url)
                            put("size", item.spec.file.size)
                            put("section", item.spec.section)
                            put("sectionName", item.spec.sectionName)
                            put("status", item.status.name)
                        }
                    }
                )
            )
        }
        this@PrivateOfflineDownloadStorage.journal.write("offline-downloads-v1", data.toString())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun observeDownloadWifi(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun wifi(): Boolean = manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(wifi())
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(wifi())
        }
        override fun onLost(network: Network) {
            trySend(wifi())
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    trySend(wifi())
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged().conflate()

suspend fun runOfflineDownloadQueue(portal: PortalRepository, accounts: AccountRepository, storage: OfflineDownloadStorage, wifiAvailable: () -> Boolean): Boolean {
    val journal = runCatching { storage.load() }.getOrElse { return false }
    val owners = accounts.accounts.first().filter { it.portal == PortalType.MOODLE }.map { it.id }.toSet()
    val items = journal.items.filter { it.spec.course.accountId in owners }.toMutableList()
    var changed = items.size != journal.items.size
    for (index in items.indices) {
        currentCoroutineContext().ensureActive()
        val item = items[index]
        if (item.status !in setOf(OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.WAITING_WIFI)) continue
        val latestOwners = accounts.accounts.first().filter { it.portal == PortalType.MOODLE }.map { it.id }.toSet()
        if (item.spec.course.accountId !in latestOwners) continue
        val cached = storage.cached(item.spec.course, item.spec.file)
        if (cached != null) {
            items[index] = item.copy(status = OfflineDownloadStatus.SAVED, bytes = cached.bytes, message = null)
            changed = true
            storage.save(OfflineDownloadJournal(items, journal.wifiOnly))
            continue
        }
        if (journal.wifiOnly && !wifiAvailable()) {
            if (item.status != OfflineDownloadStatus.WAITING_WIFI) {
                items[index] = item.copy(status = OfflineDownloadStatus.WAITING_WIFI)
                changed = true
            }
            continue
        }
        val result = portal.download(item.spec.course, item.spec.file)
        val saved = storage.cached(item.spec.course, item.spec.file)
        items[index] = if (result is AppResult.Success && saved != null) {
            item.copy(status = OfflineDownloadStatus.SAVED, bytes = saved.bytes, message = null)
        } else {
            item.copy(status = OfflineDownloadStatus.FAILED, bytes = 0, message = if (result is AppResult.Failure && result.error == AppError.SessionExpired) "Sign in again, then retry this file." else "File unavailable or download failed. Retry when connected.")
        }
        changed = true
        storage.save(OfflineDownloadJournal(items, journal.wifiOnly))
    }
    if (changed) storage.save(OfflineDownloadJournal(items, journal.wifiOnly))
    return items.any { it.status == OfflineDownloadStatus.WAITING_WIFI }
}

class OfflineDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = UitGraph.get(applicationContext)
        val storage = createOfflineDownloadStorage(applicationContext)
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val waiting = runOfflineDownloadQueue(graph.portal, graph.accounts, storage) {
            manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true
        }
        return if (waiting && runAttemptCount < 3) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context, wifiOnly: Boolean) {
            val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("uit-offline-downloads", ExistingWorkPolicy.KEEP, request)
        }
    }
}
