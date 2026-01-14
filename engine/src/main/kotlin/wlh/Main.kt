package wlh

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

enum class ScanMode {
    FAST,
    FULL,
    FAST_THEN_FULL
}

data class CrashEntry(val line: Long, val preview: String)

data class LineEntry(val line: Long, val text: String)

data class ScanResult(
    val file: String,
    val mode: String,
    val versions: List<String>,
    val crashes: List<CrashEntry>,
    val generatedAt: String
)

data class JobStatus(
    val id: String,
    val status: String,
    val progress: Int,
    val message: String? = null
)

data class EngineConfig(
    val scanPackages: List<String>
)

private data class Job(
    val id: String,
    val file: String,
    val mode: ScanMode,
    @Volatile var status: String,
    @Volatile var progress: Int,
    @Volatile var message: String? = null
)

fun main(args: Array<String>) {
    val argMap = args.toList()
    val homeIndex = argMap.indexOf("--home")
    if (homeIndex == -1 || homeIndex == argMap.lastIndex) {
        System.err.println("Missing --home")
        exitProcess(2)
    }
    val homePath = Paths.normalize(Path.of(argMap[homeIndex + 1]))
    val portIndex = argMap.indexOf("--port")
    val port = if (portIndex != -1 && portIndex < argMap.lastIndex) {
        argMap[portIndex + 1].toIntOrNull() ?: 0
    } else {
        0
    }

    val engineHome = EngineHome(homePath)
    engineHome.ensure()

    val mapper = jacksonObjectMapper()
    val jobs = ConcurrentHashMap<String, Job>()
    val jobIdCounter = AtomicInteger(1000)
    val lastResult = AtomicReference<ScanResult?>(null)
    val executor = Executors.newFixedThreadPool(2)

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val startedAt = Instant.now().toString()

    server.createContext("/") { exchange ->
        try {
            route(exchange, mapper, engineHome, jobs, jobIdCounter, lastResult, executor, startedAt)
        } catch (ex: Exception) {
            System.err.println("Handler error: ${ex.message}")
            sendJson(exchange, 500, mapper.writeValueAsString(mapOf("status" to "error", "message" to "internal_error")))
        }
    }

    server.executor = Executors.newCachedThreadPool()
    server.start()

    val actualPort = server.address.port
    engineHome.writeDaemonJson(actualPort, startedAt)

    Runtime.getRuntime().addShutdownHook(Thread {
        executor.shutdown()
    })
}

private fun route(
    exchange: HttpExchange,
    mapper: ObjectMapper,
    engineHome: EngineHome,
    jobs: ConcurrentHashMap<String, Job>,
    jobIdCounter: AtomicInteger,
    lastResult: AtomicReference<ScanResult?>,
    executor: java.util.concurrent.ExecutorService,
    startedAt: String
) {
    val method = exchange.requestMethod.uppercase(Locale.ROOT)
    val path = exchange.requestURI.path

    when {
        method == "GET" && path == "/api/v1/status" -> {
            val status = mapOf(
                "status" to "ok",
                "version" to engineHome.version,
                "pid" to ProcessHandle.current().pid(),
                "startedAt" to startedAt,
                "port" to exchange.localAddress.port
            )
            sendJson(exchange, 200, mapper.writeValueAsString(status))
        }
        method == "POST" && path == "/api/v1/shutdown" -> {
            sendJson(exchange, 200, mapper.writeValueAsString(mapOf("status" to "ok")))
            exchange.close()
            Thread {
                Thread.sleep(100)
                engineHome.deleteDaemonJson()
                exchange.httpContext.server.stop(0)
                exitProcess(0)
            }.start()
        }
        method == "POST" && path == "/api/v1/scan" -> {
            val body = readBody(exchange)
            val node = mapper.readTree(body)
            val file = node.path("file").asText(null)
            val modeRaw = node.path("mode").asText(null)
            if (file == null || modeRaw == null) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapOf("status" to "error", "message" to "missing_fields")))
                return
            }
            val mode = when (modeRaw.lowercase(Locale.ROOT)) {
                "fast" -> ScanMode.FAST
                "full" -> ScanMode.FULL
                "fast_then_full" -> ScanMode.FAST_THEN_FULL
                else -> null
            }
            if (mode == null) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapOf("status" to "error", "message" to "invalid_mode")))
                return
            }
            val target = File(file)
            if (!target.exists() || !target.isFile) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapOf("status" to "error", "message" to "file_not_found")))
                return
            }
            val jobId = jobIdCounter.incrementAndGet().toString()
            val job = Job(jobId, target.absolutePath, mode, "running", 0)
            jobs[jobId] = job
            executor.submit {
                runScan(job, target, engineHome, mapper, lastResult)
            }
            sendJson(exchange, 200, mapper.writeValueAsString(mapOf("status" to "ok", "jobId" to jobId)))
        }
        method == "GET" && path.startsWith("/api/v1/job/") -> {
            val jobId = path.removePrefix("/api/v1/job/")
            val job = jobs[jobId]
            if (job == null) {
                sendJson(exchange, 404, mapper.writeValueAsString(mapOf("status" to "error", "message" to "job_not_found")))
                return
            }
            val response = JobStatus(job.id, job.status, job.progress, job.message)
            sendJson(exchange, 200, mapper.writeValueAsString(response))
        }
        method == "GET" && path == "/api/v1/result/versions" -> {
            sendResult(exchange, mapper, lastResult.get()) { result ->
                mapOf("status" to "ok", "file" to result.file, "versions" to result.versions)
            }
        }
        method == "GET" && path == "/api/v1/result/crashes" -> {
            sendResult(exchange, mapper, lastResult.get()) { result ->
                mapOf("status" to "ok", "file" to result.file, "crashes" to result.crashes)
            }
        }
        method == "POST" && path == "/api/v1/decrypt" -> {
            val body = readBody(exchange)
            val node = mapper.readTree(body)
            val file = node.path("file").asText(null)
            val jar = node.path("jar").asText(null)
            val timeout = node.path("timeoutSeconds").asInt(30)
            if (file == null || jar == null) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapOf("status" to "error", "message" to "missing_fields")))
                return
            }
            val result = runDecrypt(file, jar, timeout)
            val code = if (result["status"] == "ok") 200 else 500
            sendJson(exchange, code, mapper.writeValueAsString(result))
        }
        method == "GET" && path == "/api/v1/adb/devices" -> {
            val query = parseQuery(exchange.requestURI)
            val adbPath = query["adb"]
            val result = runAdbDevices(adbPath)
            val code = if (result["status"] == "ok") 200 else 500
            sendJson(exchange, code, mapper.writeValueAsString(result))
        }
        method == "POST" && path == "/api/v1/adb/run" -> {
            val body = readBody(exchange)
            val node = mapper.readTree(body)
            val serial = node.path("serial").asText(null)
            val adbPath = node.path("adb").asText(null)
            val argsNode = node.path("args")
            if (serial == null || !argsNode.isArray) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapOf("status" to "error", "message" to "missing_fields")))
                return
            }
            val args = mutableListOf<String>()
            argsNode.forEach { args.add(it.asText()) }
            val result = runAdbRun(serial, args, adbPath)
            val code = if (result["status"] == "ok") 200 else 500
            sendJson(exchange, code, mapper.writeValueAsString(result))
        }
        else -> {
            sendJson(exchange, 404, mapper.writeValueAsString(mapOf("status" to "error", "message" to "not_found")))
        }
    }
}

private fun readBody(exchange: HttpExchange): String {
    return exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

private fun sendResult(
    exchange: HttpExchange,
    mapper: ObjectMapper,
    result: ScanResult?,
    builder: (ScanResult) -> Map<String, Any>
) {
    if (result == null) {
        sendJson(exchange, 200, mapper.writeValueAsString(mapOf("status" to "not_ready")))
        return
    }
    sendJson(exchange, 200, mapper.writeValueAsString(builder(result)))
}

private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun parseQuery(uri: URI): Map<String, String> {
    val query = uri.rawQuery ?: return emptyMap()
    return query.split("&").mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx == -1) return@mapNotNull null
        val key = java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8")
        val value = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        key to value
    }.toMap()
}

private fun runScan(
    job: Job,
    file: File,
    engineHome: EngineHome,
    mapper: ObjectMapper,
    lastResult: AtomicReference<ScanResult?>
) {
    try {
        val config = engineHome.readConfig(mapper)
        val cacheKey = engineHome.cacheKeyFor(file, config)
        val cached = engineHome.loadCache(cacheKey, job.mode, mapper)
        if (cached != null) {
            job.status = "completed"
            job.progress = 100
            lastResult.set(cached)
            engineHome.writeResultFile(file, cached, mapper)
            return
        }

        when (job.mode) {
            ScanMode.FAST -> {
                val result = scanFast(file, job, config)
                engineHome.saveCache(cacheKey, job.mode, result, mapper)
                lastResult.set(result)
                engineHome.writeResultFile(file, result, mapper)
            }
            ScanMode.FULL -> {
                val result = scanFull(file, job, null, config)
                engineHome.saveCache(cacheKey, job.mode, result, mapper)
                lastResult.set(result)
                engineHome.writeResultFile(file, result, mapper)
            }
            ScanMode.FAST_THEN_FULL -> {
                val fastCache = engineHome.loadCache(cacheKey, ScanMode.FAST, mapper)
                val fastResult = fastCache ?: scanFast(file, job, config)
                job.progress = 50
                val fullCache = engineHome.loadCache(cacheKey, ScanMode.FULL, mapper)
                val finalResult = fullCache ?: scanFull(file, job, fastResult, config)
                engineHome.saveCache(cacheKey, ScanMode.FULL, finalResult, mapper)
                lastResult.set(finalResult)
                engineHome.writeResultFile(file, finalResult, mapper)
            }
        }
        job.status = "completed"
        job.progress = 100
    } catch (ex: Exception) {
        job.status = "failed"
        job.message = ex.message ?: "scan_failed"
    }
}

private fun scanFast(file: File, job: Job, config: EngineConfig): ScanResult {
    val (versions, crashes) = scanByPackageWindows(file, job, config, 90)

    return ScanResult(
        file = file.absolutePath,
        mode = "fast",
        versions = versions,
        crashes = crashes,
        generatedAt = Instant.now().toString()
    )
}

private fun scanFull(file: File, job: Job, base: ScanResult?, config: EngineConfig): ScanResult {
    if (base != null) {
        return base.copy(mode = "full", generatedAt = Instant.now().toString())
    }
    val (versions, crashes) = scanByPackageWindows(file, job, config, 95)

    return ScanResult(
        file = file.absolutePath,
        mode = "full",
        versions = versions,
        crashes = crashes,
        generatedAt = Instant.now().toString()
    )
}

private fun scanByPackageWindows(
    file: File,
    job: Job,
    config: EngineConfig,
    progressCap: Int
): Pair<List<String>, List<CrashEntry>> {
    val packageFilters = config.scanPackages.map { it.lowercase(Locale.ROOT) }
    if (packageFilters.isEmpty()) {
        return Pair(emptyList(), emptyList())
    }

    val versions = linkedSetOf<String>()
    val crashes = mutableListOf<CrashEntry>()
    val crashLines = mutableSetOf<Long>()
    val versionRegex = Regex("(?i)\\bversion[:=\\s]+([0-9][0-9A-Za-z._-]*)")
    val crashMarkers = listOf("fatal exception", "anr", "fatal signal", "sigsegv", "native crash", "crash")

    val rgResult = runRgScan(file, packageFilters, versionRegex, crashMarkers)
    if (rgResult != null) {
        versions.addAll(rgResult.first)
        crashes.addAll(rgResult.second)
        job.progress = progressCap
        return Pair(versions.toList(), crashes)
    }

    val totalSize = file.length().coerceAtLeast(1L)
    var processedBytes = 0L
    var lineNumber = 0L
    val prevLines = ArrayDeque<LineEntry>(3)
    val activeWindows = mutableListOf<Int>()

    fun processLine(lineNo: Long, line: String) {
        versionRegex.find(line)?.let { match ->
            versions.add(match.groupValues[1])
        }
        val lower = line.lowercase(Locale.ROOT)
        if (crashMarkers.any { lower.contains(it) } && crashLines.add(lineNo)) {
            crashes.add(CrashEntry(lineNo, line.take(200)))
        }
    }

    file.bufferedReader().use { reader ->
        var line = reader.readLine()
        while (line != null) {
            lineNumber += 1
            processedBytes += line.length + 1

            val lower = line.lowercase(Locale.ROOT)
            val existingWindows = activeWindows.size
            if (existingWindows > 0) {
                processLine(lineNumber, line)
            }

            if (packageFilters.any { lower.contains(it) }) {
                for (entry in prevLines) {
                    processLine(entry.line, entry.text)
                }
                if (existingWindows == 0) {
                    processLine(lineNumber, line)
                }
                activeWindows.add(20)
            }

            if (existingWindows > 0) {
                for (i in existingWindows - 1 downTo 0) {
                    val remaining = activeWindows[i] - 1
                    if (remaining <= 0) {
                        activeWindows.removeAt(i)
                    } else {
                        activeWindows[i] = remaining
                    }
                }
            }

            prevLines.addLast(LineEntry(lineNumber, line))
            if (prevLines.size > 3) {
                prevLines.removeFirst()
            }

            if (lineNumber % 2000L == 0L) {
                job.progress = ((processedBytes.toDouble() / totalSize) * 100).toInt().coerceIn(0, progressCap)
            }
            line = reader.readLine()
        }
    }

    return Pair(versions.toList(), crashes)
}

private fun runRgScan(
    file: File,
    packageFilters: List<String>,
    versionRegex: Regex,
    crashMarkers: List<String>
): Pair<List<String>, List<CrashEntry>>? {
    val args = mutableListOf("rg", "-n", "-B", "3", "-A", "20", "-i")
    packageFilters.forEach { args.addAll(listOf("-e", it)) }
    args.add(file.absolutePath)

    val process = try {
        ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
    } catch (_: Exception) {
        return null
    }

    val versions = linkedSetOf<String>()
    val crashes = mutableListOf<CrashEntry>()
    val crashLines = mutableSetOf<Long>()
    val lineSeen = mutableSetOf<Long>()
    val lineRegex = Regex("^(\\d+)[-:](.*)$")

    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { raw ->
            if (raw == "--") return@forEach
            val match = lineRegex.find(raw) ?: return@forEach
            val lineNo = match.groupValues[1].toLongOrNull() ?: return@forEach
            if (!lineSeen.add(lineNo)) return@forEach
            val line = match.groupValues[2]
            versionRegex.find(line)?.let { versions.add(it.groupValues[1]) }
            val lower = line.lowercase(Locale.ROOT)
            if (crashMarkers.any { lower.contains(it) } && crashLines.add(lineNo)) {
                crashes.add(CrashEntry(lineNo, line.take(200)))
            }
        }
    }

    return if (process.waitFor() == 0) {
        Pair(versions.toList(), crashes)
    } else {
        null
    }
}

private fun runDecrypt(file: String, jar: String, timeoutSeconds: Int): Map<String, Any> {
    val input = File(file)
    val output = File(file + ".txt")
    if (output.exists()) {
        output.delete()
    }

    val javaCmd = resolveJava()
    if (javaCmd == null) {
        return mapOf("status" to "error", "message" to "java_not_found")
    }

    val process = try {
        ProcessBuilder(listOf(javaCmd, "-jar", jar, input.absolutePath, output.absolutePath))
            .redirectErrorStream(true)
            .start()
    } catch (ex: Exception) {
        return mapOf("status" to "error", "message" to "decrypt_launch_failed")
    }

    val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return mapOf("status" to "error", "message" to "timeout", "input" to input.absolutePath, "output" to output.absolutePath)
    }

    val hasOutput = output.exists() && output.length() > 0L
    return if (hasOutput) {
        mapOf("status" to "ok", "input" to input.absolutePath, "output" to output.absolutePath)
    } else {
        mapOf("status" to "error", "message" to "decrypt_failed", "input" to input.absolutePath, "output" to output.absolutePath)
    }
}

private fun resolveJava(): String? {
    val javaHome = System.getenv("JAVA_HOME")
    if (!javaHome.isNullOrBlank()) {
        val candidate = File(javaHome, "bin/java")
        if (candidate.exists()) return candidate.absolutePath
    }
    return "java"
}

private fun runAdbDevices(adbPath: String?): Map<String, Any> {
    val adb = adbPath ?: "adb"
    return try {
        val output = runProcess(listOf(adb, "devices", "-l"))
        val lines = output.lines().filter { it.isNotBlank() }
        mapOf("status" to "ok", "raw" to output, "lines" to lines)
    } catch (ex: Exception) {
        mapOf("status" to "error", "message" to (ex.message ?: "adb_failed"))
    }
}

private fun runAdbRun(serial: String, args: List<String>, adbPath: String?): Map<String, Any> {
    val adb = adbPath ?: "adb"
    return try {
        val cmd = mutableListOf(adb, "-s", serial)
        cmd.addAll(args)
        val output = runProcess(cmd)
        mapOf("status" to "ok", "raw" to output)
    } catch (ex: Exception) {
        mapOf("status" to "error", "message" to (ex.message ?: "adb_failed"))
    }
}

private fun runProcess(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
    val code = process.waitFor()
    if (code != 0) {
        throw IllegalStateException("command_failed")
    }
    return output
}

private class EngineHome(private val home: Path) {
    val version: String = "1.0.0"
    private val engineDir = home.resolve("engine")
    private val daemonDir = home.resolve("daemon")
    private val cacheDir = home.resolve("cache")
    private val configDir = home.resolve("config")
    private val configFile = configDir.resolve("wlh.json")

    fun ensure() {
        Files.createDirectories(engineDir)
        Files.createDirectories(daemonDir)
        Files.createDirectories(cacheDir)
        Files.createDirectories(configDir)
        Files.createDirectories(home.resolve("logs"))
    }

    fun writeDaemonJson(port: Int, startedAt: String) {
        val json = """
            {
              "port": $port,
              "pid": ${ProcessHandle.current().pid()},
              "startedAt": "$startedAt",
              "apiVersion": 1
            }
        """.trimIndent()
        val target = daemonDir.resolve("daemon.json")
        val temp = daemonDir.resolve("daemon.json.tmp")
        Files.writeString(temp, json, StandardCharsets.UTF_8)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun deleteDaemonJson() {
        val target = daemonDir.resolve("daemon.json")
        Files.deleteIfExists(target)
    }

    fun cacheKeyFor(file: File, config: EngineConfig): String {
        val configKey = config.scanPackages.joinToString(",")
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}:${configKey}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun loadCache(key: String, mode: ScanMode, mapper: ObjectMapper): ScanResult? {
        val path = cacheDir.resolve("$key-${mode.name.lowercase(Locale.ROOT)}.json")
        if (!Files.exists(path)) return null
        return mapper.readValue(path.toFile())
    }

    fun saveCache(key: String, mode: ScanMode, result: ScanResult, mapper: ObjectMapper) {
        val path = cacheDir.resolve("$key-${mode.name.lowercase(Locale.ROOT)}.json")
        mapper.writeValue(path.toFile(), result)
    }

    fun writeResultFile(file: File, result: ScanResult, mapper: ObjectMapper) {
        val resultPath = Path.of(file.absolutePath + ".wlhresult")
        val tempPath = Path.of(resultPath.toString() + ".tmp")
        mapper.writeValue(tempPath.toFile(), result)
        Files.move(tempPath, resultPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun readConfig(mapper: ObjectMapper): EngineConfig {
        if (!Files.exists(configFile)) {
            return EngineConfig(emptyList())
        }
        return try {
            val node = mapper.readTree(configFile.toFile())
            val packages = node.path("scanPackages").takeIf { it.isArray }?.map { it.asText() } ?: emptyList()
            EngineConfig(
                packages.filter { it.isNotBlank() }
            )
        } catch (ex: Exception) {
            EngineConfig(emptyList())
        }
    }
}

private object Paths {
    fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
}
