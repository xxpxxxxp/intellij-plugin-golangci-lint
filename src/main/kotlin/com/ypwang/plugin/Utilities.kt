package com.ypwang.plugin

import com.goide.sdk.GoSdkService
import com.google.common.io.CharStreams
import com.google.gson.Gson
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.ypwang.plugin.model.GithubRelease
import com.ypwang.plugin.model.GoLinter
import com.ypwang.plugin.model.RunProcessResult
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

private const val notificationGroupName = "Go linter notifications"
private val configFiles = arrayOf(".golangci.json", ".golangci.toml", ".golangci.yaml", ".golangci.yml")  // ordered by precedence

val logger = Logger.getInstance("go-linter")
val notificationGroup: NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(notificationGroupName)

fun findCustomConfigInPath(path: String?): Optional<String> {
    val varPath: String? = path
    if (varPath != null) {
        var cur: Path? = Paths.get(varPath)
        while (cur != null && cur.toFile().isDirectory) {
            for (s in configFiles) {
                val f = cur.resolve(s).toFile()
                if (f.exists() && f.isFile) { // found a valid config file
                    return Optional.of(f.path)
                }
            }
            cur = cur.parent
        }
    }

    return Optional.empty()
}

private class OutputReader(val inputStream: InputStream, val consumer: ByteArrayOutputStream) : Runnable {
    override fun run() = try {
        val buf = ByteArray(1024)
        while (true) {
            val count = inputStream.read(buf)
            if (count == -1) break
            consumer.write(buf, 0, count)
        }
    } catch (e: IOException) {
        Logger.getInstance(this.javaClass).error(e)
    }

    companion object {
        fun fetch(inputStream: InputStream, consumer: ByteArrayOutputStream): Thread {
            val thread = Thread(OutputReader(inputStream, consumer))
            thread.start()
            return thread
        }
    }
}

fun fetchProcessOutput(process: Process, encoding: Charset): RunProcessResult {
    val outputConsumer = ByteArrayOutputStream()
    val outputThread = OutputReader.fetch(process.inputStream, outputConsumer)
    val errorConsumer = ByteArrayOutputStream()
    val errorThread = OutputReader.fetch(process.errorStream, errorConsumer)

    try {
        val returnCode = process.waitFor()
        errorThread.join()
        outputThread.join()

        return RunProcessResult(returnCode, outputConsumer.toString(encoding), errorConsumer.toString(encoding))
    } catch (e: InterruptedException) {
        logger.error(e)
    }

    return RunProcessResult(-1, "", "")
}

fun parseLinters(project: Project, result: RunProcessResult): List<GoLinter> {
    when (result.returnCode) {
        0 -> {
            // continue
        }
        2 ->
            if (isGo18(project))
                throw Exception("Incompatible golangci-lint with Go1.18, please update to version after 1.45.0")
            else
                throw Exception("golangci-lint panic: $result")
        else ->
            throw Exception("Failed to Discover Linters: $result")
    }

    val linters = mutableListOf<GoLinter>()
    // format: name[ (aka)][ deprecated]: description [fast: bool, auto-fix: bool]
    val regex = Regex("""(?<name>\w+)( \((?<aka>[\w, ]+)\))?( \[(?<deprecated>deprecated)])?: (?<description>.+) \[fast: (?<fast>true|false), auto-fix: (?<autofix>true|false)]""")
    // parse output
    var enabled = true
    for (line in result.stdout.lines()) {
        if (line.isEmpty())
            continue

        if (line.startsWith("Enabled")) {
            enabled = true
            continue
        }
        if (line.startsWith("Disabled")) {
            enabled = false
            continue
        }

        // use regex is a bit slow
        regex.matchEntire(line)?.let {
            linters.add(
                GoLinter(
                    enabled,
                    it.groups["name"]!!.value,
                    it.groups["aka"]?.value ?: "",
                    it.groups["deprecated"]?.value == "deprecated",
                    it.groups["description"]!!.value,
                    it.groups["fast"]!!.value.toBoolean(),
                    it.groups["autofix"]!!.value.toBoolean(),
                )
            )
        }
    }

    return linters
}

fun getLatestReleaseMeta(httpClient: CloseableHttpClient): GithubRelease =
        Gson().fromJson(
            httpClient.execute(HttpGet("https://api.github.com/repos/golangci/golangci-lint/releases/latest")).use { response ->
                CharStreams.toString(InputStreamReader(response.entity.content, Charset.defaultCharset()))
            },
            GithubRelease::class.java
        )

fun isGo18(project: Project): Boolean {
    val sdk = GoSdkService.getInstance(project).getSdk(null)
    return sdk.version != null && compareVersion(sdk.version!!, "1.18") >= 0
}

fun compareVersion(v1: String, v2: String): Int =
    when (val unmatched = v1.split('.').zip(v2.split('.')).firstOrNull { it.first != it.second }) {
        null -> 0
        else -> {
            val (a, b) = unmatched
            a.toInt().compareTo(b.toInt())
        }
    }