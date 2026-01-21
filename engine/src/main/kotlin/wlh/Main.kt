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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

data class CrashEntry(val line: Long, val preview: String, val packageName: String)

data class VersionEntry(val line: Long, val label: String, val packageName: String)

data class BuildPropEntry(val key: String, val value: String)

data class ScanResult(
    val file: String,
    val buildProps: List<BuildPropEntry>,
    val versions: List<VersionEntry>,
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
            val force = node.path("force").asBoolean(false)
            if (file == null) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapOf("status" to "error", "message" to "missing_fields")))
                return
            }
            val target = File(file)
            if (!target.exists() || !target.isFile) {
                sendJson(exchange, 400, mapper.writeValueAsString(mapOf("status" to "error", "message" to "file_not_found")))
                return
            }
            val jobId = jobIdCounter.incrementAndGet().toString()
            val job = Job(jobId, target.absolutePath, "running", 0)
            jobs[jobId] = job
            executor.submit {
                runScan(job, target, engineHome, mapper, lastResult, force)
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
    lastResult: AtomicReference<ScanResult?>,
    force: Boolean
) {
    try {
        val config = engineHome.readConfig(mapper)
        val cacheKey = engineHome.cacheKeyFor(file, config)
        val cached = if (force) null else engineHome.loadCache(cacheKey, mapper)
        if (cached != null) {
            job.status = "completed"
            job.progress = 100
            lastResult.set(cached)
            engineHome.writeResultFile(file, cached, mapper)
            return
        }
        val result = scanFull(file, job, config)
        engineHome.saveCache(cacheKey, result, mapper)
        lastResult.set(result)
        engineHome.writeResultFile(file, result, mapper)
        job.status = "completed"
        job.progress = 100
    } catch (ex: Exception) {
        job.status = "failed"
        job.message = ex.message ?: "scan_failed"
    }
}

private fun scanFull(file: File, job: Job, config: EngineConfig): ScanResult {
    val buildProps = scanBuildProps(file)
    val versions = scanPackageVersions(file, config)
    val crashes = scanFatalCrashes(file, job, config, 95)

    return ScanResult(
        file = file.absolutePath,
        buildProps = buildProps,
        versions = versions,
        crashes = crashes,
        generatedAt = Instant.now().toString()
    )
}

private fun scanBuildProps(file: File): List<BuildPropEntry> {
    val keys = listOf(
        "ro.build.changelist",
        "ro.build.flavor",
        "ro.build.version.oneui",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.version.sem",
        "ro.build.version.sep",
        "ro.csc.country_code",
        "ro.csc.countryiso_code",
        "ro.csc.sales_code",
        "ro.omc.build.id",
        "ro.omc.build.version",
        "ro.product.build.type",
        "ro.product.model"
    )
    val keySet = keys.toSet()
    val found = LinkedHashMap<String, String>()
    val lineRegex = Regex("\\[([^\\]]+)]\\s*:\\s*\\[([^\\]]*)]")

    file.bufferedReader().use { reader ->
        var line = reader.readLine()
        while (line != null) {
            val match = lineRegex.find(line)
            if (match != null) {
                val key = match.groupValues[1].trim()
                if (keySet.contains(key) && !found.containsKey(key)) {
                    found[key] = match.groupValues[2].trim()
                    if (found.size == keySet.size) {
                        break
                    }
                }
            }
            line = reader.readLine()
        }
    }

    return keys.mapNotNull { key ->
        val value = found[key] ?: return@mapNotNull null
        BuildPropEntry(key, value)
    }
}

private fun scanFatalCrashes(
    file: File,
    job: Job,
    config: EngineConfig,
    progressCap: Int
): List<CrashEntry> {
    val packageFilters = config.scanPackages.map { it.lowercase(Locale.ROOT) }
    if (packageFilters.isEmpty()) {
        return emptyList()
    }

    val crashes = mutableListOf<CrashEntry>()
    val crashLines = mutableSetOf<Long>()
    val crashMarker = "FATAL EXCEPTION:"
    val appCrashedMarker = "APP CRASHED"
    val anrRegex = Regex("ANR in\\s+([0-9A-Za-z._-]+)")

    val rgResult = runRgFatalScan(file, packageFilters, crashMarker, appCrashedMarker, anrRegex)
    if (rgResult != null) {
        crashes.addAll(rgResult)
        job.progress = progressCap
        return crashes
    }

    val totalSize = file.length().coerceAtLeast(1L)
    var processedBytes = 0L
    var lineNumber = 0L

    file.bufferedReader().use { reader ->
        var carry: String? = null
        var carryLineNumber = 0L
        while (true) {
            val fromCarry = carry != null
            val line = if (fromCarry) {
                val value = carry
                carry = null
                value
            } else {
                reader.readLine()
            } ?: break

            if (fromCarry) {
                lineNumber = carryLineNumber
            } else {
                lineNumber += 1
                processedBytes += line.length + 1
            }

            if (line.contains(crashMarker)) {
                val fatalLineNumber = lineNumber
                val fatalLine = line
                val processLine = reader.readLine() ?: break
                lineNumber += 1
                processedBytes += processLine.length + 1
                val matchedPackage = findMatchingPackage(processLine, packageFilters)
                if (processLine.contains("Process:") && matchedPackage != null) {
                    val blockLines = mutableListOf(fatalLine, processLine)
                    var lookahead = 0
                    while (lookahead < 3) {
                        val next = reader.readLine()
                        if (next == null) {
                            break
                        }
                        lineNumber += 1
                        processedBytes += next.length + 1
                        lookahead += 1
                        if (next.contains("AndroidRuntime", ignoreCase = true)) {
                            blockLines.add(next)
                        } else {
                            carry = next
                            carryLineNumber = lineNumber
                            break
                        }
                    }
                    if (crashLines.add(fatalLineNumber)) {
                        val formatted = formatCrashLines(blockLines)
                        crashes.add(CrashEntry(fatalLineNumber, formatted.joinToString("\n"), matchedPackage))
                    }
                    if (carry != null) {
                        continue
                    }
                }
            } else if (line.contains(appCrashedMarker)) {
                val crashLineNumber = lineNumber
                val crashLine = line
                val nextLine = reader.readLine() ?: break
                lineNumber += 1
                processedBytes += nextLine.length + 1
                val tagIndex = nextLine.indexOf("CRASH:", ignoreCase = true)
                if (tagIndex != -1) {
                    val packageName = nextLine.substring(tagIndex + "CRASH:".length).trim()
                        .split(Regex("\\s+"))
                        .firstOrNull()
                        ?.lowercase(Locale.ROOT)
                    if (packageName != null && packageFilters.contains(packageName)) {
                        val blockLines = mutableListOf(crashLine, nextLine)
                        var added = 0
                        while (added < 3) {
                            val next = reader.readLine() ?: break
                            lineNumber += 1
                            processedBytes += next.length + 1
                            if (next.contains("CRASH", ignoreCase = true)) {
                                blockLines.add(next)
                                added += 1
                            } else {
                                carry = next
                                carryLineNumber = lineNumber
                                break
                            }
                        }
                        if (crashLines.add(crashLineNumber)) {
                            val formatted = formatCrashLines(blockLines)
                            crashes.add(CrashEntry(crashLineNumber, formatted.joinToString("\n"), packageName))
                        }
                        if (carry != null) {
                            continue
                        }
                    }
                }
            } else {
                val anrMatch = anrRegex.find(line)
                if (anrMatch != null) {
                    val packageName = anrMatch.groupValues[1].lowercase(Locale.ROOT)
                    if (packageFilters.contains(packageName)) {
                        val anrLineNumber = lineNumber
                        val blockLines = mutableListOf(line)
                        var added = 0
                        while (added < 3) {
                            val next = reader.readLine() ?: break
                            lineNumber += 1
                            processedBytes += next.length + 1
                            blockLines.add(next)
                            added += 1
                        }
                        if (crashLines.add(anrLineNumber)) {
                            val formatted = formatCrashLines(blockLines)
                            crashes.add(CrashEntry(anrLineNumber, formatted.joinToString("\n"), packageName))
                        }
                    }
                }
            }

            if (lineNumber % 2000L == 0L) {
                job.progress = ((processedBytes.toDouble() / totalSize) * 100).toInt().coerceIn(0, progressCap)
            }
        }
    }

    return crashes
}

private fun runRgFatalScan(
    file: File,
    packageFilters: List<String>,
    crashMarker: String,
    appCrashedMarker: String,
    anrRegex: Regex
): List<CrashEntry>? {
    val args = mutableListOf("rg", "--text", "-n", "-A", "6", crashMarker, "-e", appCrashedMarker, "-e", "ANR in ")
    args.add(file.absolutePath)

    val process = try {
        ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
    } catch (_: Exception) {
        return null
    }

    val crashes = mutableListOf<CrashEntry>()
    val crashLines = mutableSetOf<Long>()
    val lineSeen = mutableSetOf<Long>()
    val lineRegex = Regex("^(\\d+)[-:](.*)$")
    val blockLines = mutableListOf<Pair<Long, String>>()

    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { raw ->
            if (raw == "--") {
                processFatalBlock(blockLines, packageFilters, crashMarker, appCrashedMarker, anrRegex, crashes, crashLines)
                blockLines.clear()
                return@forEach
            }
            val match = lineRegex.find(raw) ?: return@forEach
            val lineNo = match.groupValues[1].toLongOrNull() ?: return@forEach
            if (!lineSeen.add(lineNo)) return@forEach
            blockLines.add(lineNo to match.groupValues[2])
        }
    }
    if (blockLines.isNotEmpty()) {
        processFatalBlock(blockLines, packageFilters, crashMarker, appCrashedMarker, anrRegex, crashes, crashLines)
    }

    return if (process.waitFor() == 0) crashes else null
}

private fun processFatalBlock(
    blockLines: List<Pair<Long, String>>,
    packageFilters: List<String>,
    crashMarker: String,
    appCrashedMarker: String,
    anrRegex: Regex,
    crashes: MutableList<CrashEntry>,
    crashLines: MutableSet<Long>
) {
    val fatalIndex = blockLines.indexOfFirst { it.second.contains(crashMarker) }
    if (fatalIndex != -1 && fatalIndex + 1 < blockLines.size) {
        val (fatalLineNumber, fatalLine) = blockLines[fatalIndex]
        val processLine = blockLines[fatalIndex + 1].second
        val matchedPackage = findMatchingPackage(processLine, packageFilters)
        if (!processLine.contains("Process:") || matchedPackage == null) {
            return
        }
        val block = mutableListOf(fatalLine, processLine)
        var added = 0
        for (i in fatalIndex + 2 until blockLines.size) {
            if (added >= 3) break
            val line = blockLines[i].second
            if (line.contains("AndroidRuntime", ignoreCase = true)) {
                block.add(line)
                added += 1
            } else {
                break
            }
        }
        if (crashLines.add(fatalLineNumber)) {
            val formatted = formatCrashLines(block)
            crashes.add(CrashEntry(fatalLineNumber, formatted.joinToString("\n"), matchedPackage))
        }
        return
    }

    val crashIndex = blockLines.indexOfFirst { it.second.contains(appCrashedMarker) }
    if (crashIndex == -1 || crashIndex + 1 >= blockLines.size) {
        return
    }
    val (crashLineNumber, crashLine) = blockLines[crashIndex]
    val nextLine = blockLines[crashIndex + 1].second
    val tagIndex = nextLine.indexOf("CRASH:", ignoreCase = true)
    if (tagIndex == -1) {
        return
    }
    val packageName = nextLine.substring(tagIndex + "CRASH:".length).trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        ?.lowercase(Locale.ROOT)
        ?: return
    if (!packageFilters.contains(packageName)) {
        return
    }
    val block = mutableListOf(crashLine, nextLine)
    var added = 0
    for (i in crashIndex + 2 until blockLines.size) {
        if (added >= 3) break
        val line = blockLines[i].second
        if (line.contains("CRASH", ignoreCase = true)) {
            block.add(line)
            added += 1
        } else {
            break
        }
    }
    if (crashLines.add(crashLineNumber)) {
        val formatted = formatCrashLines(block)
        crashes.add(CrashEntry(crashLineNumber, formatted.joinToString("\n"), packageName))
    }

    val anrIndex = blockLines.indexOfFirst { anrRegex.containsMatchIn(it.second) }
    if (anrIndex == -1) {
        return
    }
    val (anrLineNumber, anrLine) = blockLines[anrIndex]
    val match = anrRegex.find(anrLine) ?: return
    val anrPackage = match.groupValues[1].lowercase(Locale.ROOT)
    if (!packageFilters.contains(anrPackage)) {
        return
    }
    val anrBlock = mutableListOf(anrLine)
    var anrAdded = 0
    for (i in anrIndex + 1 until blockLines.size) {
        if (anrAdded >= 3) break
        anrBlock.add(blockLines[i].second)
        anrAdded += 1
    }
    if (crashLines.add(anrLineNumber)) {
        val formatted = formatCrashLines(anrBlock)
        crashes.add(CrashEntry(anrLineNumber, formatted.joinToString("\n"), anrPackage))
    }
}

private fun findMatchingPackage(line: String, packageFilters: List<String>): String? {
    val lower = line.lowercase(Locale.ROOT)
    return packageFilters.firstOrNull { lower.contains(it) }
}

private fun formatCrashLines(lines: List<String>): List<String> {
    if (lines.isEmpty()) return lines
    val tokenRegex = Regex("^[0-9:\\-\\.]+$")
    val tokensPerLine = lines.map { it.trim().split(Regex("[\\t ]+")) }
    val hasFiveTokens = tokensPerLine.all { it.size >= 6 }
    if (!hasFiveTokens) return lines
    val leadingTokensMatch = tokensPerLine.all { parts ->
        parts.take(5).all { tokenRegex.matches(it) }
    }
    if (!leadingTokensMatch) return lines
    return tokensPerLine.map { parts ->
        parts.drop(5).joinToString(" ")
    }
}

private fun scanPackageVersions(file: File, config: EngineConfig): List<VersionEntry> {
    val packageFilters = config.scanPackages.map { it.lowercase(Locale.ROOT) }.toSet()
    if (packageFilters.isEmpty()) {
        return emptyList()
    }
    val rgResult = runRgVersionScan(file, packageFilters)
    if (rgResult != null) {
        return rgResult
    }
    return scanPackageVersionsStream(file, packageFilters)
}

private fun scanPackageVersionsStream(file: File, packageFilters: Set<String>): List<VersionEntry> {
    val versions = linkedSetOf<VersionEntry>()
    val packageRegex = Regex("Package \\[([^\\]]+)] \\(([0-9A-Za-z]+)\\):")
    val mPackageRegex = Regex("mPackageName='([^']+)'")
    val versionCodeRegex = Regex("versionCode\\s*[:=]\\s*([0-9A-Za-z._-]+)", RegexOption.IGNORE_CASE)
    val versionNameRegex = Regex("versionName\\s*[:=]\\s*([0-9A-Za-z._-]+)", RegexOption.IGNORE_CASE)
    val versionCodeAltRegex = Regex("VersionCode:\\s*([0-9]+)", RegexOption.IGNORE_CASE)
    val versionNameAltRegex = Regex("VersionName:\\s*([0-9.]+)", RegexOption.IGNORE_CASE)
    file.bufferedReader().use { reader ->
        var lineNumber = 0L
        var line = reader.readLine()
        while (line != null) {
            lineNumber += 1
            val match = packageRegex.find(line)
            if (match != null) {
                val packageName = match.groupValues[1].lowercase(Locale.ROOT)
                if (packageFilters.contains(packageName)) {
                    var versionCode: String? = null
                    var versionName: String? = null
                    var codePathFound = false
                    var systemApp = false
                    var lookahead = 0
                    val headerLine = lineNumber
                    while (lookahead < 30) {
                        val next = reader.readLine() ?: break
                        lineNumber += 1
                        lookahead += 1
                        if (!codePathFound && next.contains("codePath", ignoreCase = true)) {
                            codePathFound = true
                            if (next.contains("/system/app", ignoreCase = true)) {
                                systemApp = true
                            }
                        }
                        if (versionCode == null) {
                            versionCode = versionCodeRegex.find(next)?.groupValues?.get(1)
                        }
                        if (versionName == null) {
                            versionName = versionNameRegex.find(next)?.groupValues?.get(1)
                        }
                        if (versionCode != null && versionName != null && codePathFound) {
                            break
                        }
                    }
                    if (versionCode != null && versionName != null) {
                        val label = buildString {
                            append(versionName)
                            append(" (")
                            append(versionCode)
                            append(")")
                            if (codePathFound && systemApp) {
                                append(" [System]")
                            }
                        }
                        versions.add(VersionEntry(headerLine, label, packageName))
                    }
                }
            } else {
                val altMatch = mPackageRegex.find(line)
                if (altMatch != null) {
                    val packageName = altMatch.groupValues[1].lowercase(Locale.ROOT)
                    if (packageFilters.contains(packageName)) {
                        var versionCode: String? = null
                        var versionName: String? = null
                        var lookahead = 0
                        val headerLine = lineNumber
                        while (lookahead < 3) {
                            val next = reader.readLine() ?: break
                            lineNumber += 1
                            lookahead += 1
                            if (versionCode == null) {
                                versionCode = versionCodeAltRegex.find(next)?.groupValues?.get(1)
                            }
                            if (versionName == null) {
                                versionName = versionNameAltRegex.find(next)?.groupValues?.get(1)
                            }
                            if (versionCode != null && versionName != null) {
                                break
                            }
                        }
                        if (versionCode != null && versionName != null) {
                            val label = "${versionName} (${versionCode})"
                            versions.add(VersionEntry(headerLine, label, packageName))
                        }
                    }
                }
            }
            line = reader.readLine()
        }
    }
    return versions.toList()
}

private fun runRgVersionScan(file: File, packageFilters: Set<String>): List<VersionEntry>? {
    val args = mutableListOf(
        "rg",
        "--text",
        "-n",
        "-A",
        "30",
        "-e",
        "Package \\[",
        "-e",
        "mPackageName='",
        file.absolutePath
    )
    val process = try {
        ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
    } catch (_: Exception) {
        return null
    }
    val versions = linkedSetOf<VersionEntry>()
    val packageRegex = Regex("Package \\[([^\\]]+)] \\(([0-9A-Za-z]+)\\):")
    val mPackageRegex = Regex("mPackageName='([^']+)'")
    val versionCodeRegex = Regex("versionCode\\s*[:=]\\s*([0-9A-Za-z._-]+)", RegexOption.IGNORE_CASE)
    val versionNameRegex = Regex("versionName\\s*[:=]\\s*([0-9A-Za-z._-]+)", RegexOption.IGNORE_CASE)
    val versionCodeAltRegex = Regex("VersionCode:\\s*([0-9]+)", RegexOption.IGNORE_CASE)
    val versionNameAltRegex = Regex("VersionName:\\s*([0-9.]+)", RegexOption.IGNORE_CASE)
    val lineRegex = Regex("^(\\d+)[-:](.*)$")

    var active = false
    var remaining = 0
    var codePathFound = false
    var systemApp = false
    var versionCode: String? = null
    var versionName: String? = null
    var headerLineNumber: Long? = null
    var currentPackage: String? = null
    var altActive = false
    var altRemaining = 0
    var altVersionCode: String? = null
    var altVersionName: String? = null
    var altHeaderLine: Long? = null
    var altPackageName: String? = null

    fun flush() {
        val pkg = currentPackage
        if (pkg != null && versionCode != null && versionName != null && headerLineNumber != null) {
            val label = buildString {
                append(versionName)
                append(" (")
                append(versionCode)
                append(")")
                if (codePathFound && systemApp) {
                    append(" [System]")
                }
            }
            versions.add(VersionEntry(headerLineNumber!!, label, pkg))
        }
        active = false
        remaining = 0
        codePathFound = false
        systemApp = false
        versionCode = null
        versionName = null
        headerLineNumber = null
        currentPackage = null
    }

    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { raw ->
            if (raw == "--") {
                if (active) {
                    flush()
                }
                if (altActive) {
                    if (altHeaderLine != null && altVersionCode != null && altVersionName != null) {
                        versions.add(VersionEntry(altHeaderLine!!, "${altVersionName} (${altVersionCode})", altPackageName ?: ""))
                    }
                    altActive = false
                    altRemaining = 0
                    altVersionCode = null
                    altVersionName = null
                    altHeaderLine = null
                    altPackageName = null
                }
                return@forEach
            }
            val match = lineRegex.find(raw) ?: return@forEach
            val line = match.groupValues[2]
            val lineNumber = match.groupValues[1].toLongOrNull()
            val headerMatch = packageRegex.find(line)
            if (headerMatch != null) {
                if (active) {
                    flush()
                }
                if (altActive) {
                    if (altHeaderLine != null && altVersionCode != null && altVersionName != null) {
                        versions.add(VersionEntry(altHeaderLine!!, "${altVersionName} (${altVersionCode})", altPackageName ?: ""))
                    }
                    altActive = false
                    altRemaining = 0
                    altVersionCode = null
                    altVersionName = null
                    altHeaderLine = null
                    altPackageName = null
                }
                val packageName = headerMatch.groupValues[1].lowercase(Locale.ROOT)
                if (packageFilters.contains(packageName)) {
                    active = true
                    remaining = 30
                    currentPackage = packageName
                    headerLineNumber = lineNumber
                }
                return@forEach
            }
            if (!active && !altActive) {
                val altMatch = mPackageRegex.find(line)
                if (altMatch != null) {
                    val packageName = altMatch.groupValues[1].lowercase(Locale.ROOT)
                    if (packageFilters.contains(packageName)) {
                        altActive = true
                        altRemaining = 3
                        altHeaderLine = lineNumber
                        altPackageName = packageName
                        altVersionCode = null
                        altVersionName = null
                    }
                }
            } else if (altActive && altRemaining > 0) {
                if (altVersionCode == null) {
                    altVersionCode = versionCodeAltRegex.find(line)?.groupValues?.get(1)
                }
                if (altVersionName == null) {
                    altVersionName = versionNameAltRegex.find(line)?.groupValues?.get(1)
                }
                altRemaining -= 1
                if (altRemaining == 0 || (altVersionCode != null && altVersionName != null)) {
                    if (altHeaderLine != null && altVersionCode != null && altVersionName != null) {
                        versions.add(VersionEntry(altHeaderLine!!, "${altVersionName} (${altVersionCode})", altPackageName ?: ""))
                    }
                    altActive = false
                    altRemaining = 0
                    altVersionCode = null
                    altVersionName = null
                    altHeaderLine = null
                    altPackageName = null
                }
            }
            if (!active || remaining <= 0) {
                return@forEach
            }
            remaining -= 1
            if (!codePathFound && line.contains("codePath", ignoreCase = true)) {
                codePathFound = true
                if (line.contains("/system/app", ignoreCase = true)) {
                    systemApp = true
                }
            }
            if (versionCode == null) {
                versionCode = versionCodeRegex.find(line)?.groupValues?.get(1)
            }
            if (versionName == null) {
                versionName = versionNameRegex.find(line)?.groupValues?.get(1)
            }
        }
    }

    if (active) {
        flush()
    }

    return if (process.waitFor() == 0) versions.toList() else null
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

    val drainThread = Thread {
        process.inputStream.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
            }
        }
    }
    drainThread.isDaemon = true
    drainThread.start()

    val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return mapOf("status" to "error", "message" to "timeout", "input" to input.absolutePath, "output" to output.absolutePath)
    }
    drainThread.join(1000)

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

    fun loadCache(key: String, mapper: ObjectMapper): ScanResult? {
        val path = cacheDir.resolve("$key-scan.json")
        if (!Files.exists(path)) return null
        return mapper.readValue(path.toFile())
    }

    fun saveCache(key: String, result: ScanResult, mapper: ObjectMapper) {
        val path = cacheDir.resolve("$key-scan.json")
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
