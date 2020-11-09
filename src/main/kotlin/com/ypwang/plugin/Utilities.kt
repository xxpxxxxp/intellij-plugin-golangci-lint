package com.ypwang.plugin

import com.google.common.io.CharStreams
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.notification.NotificationGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.ypwang.plugin.model.GithubRelease
import com.ypwang.plugin.model.GolangciLintVersion
import com.ypwang.plugin.model.RunProcessResult
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder
import java.io.*
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

private const val LinterName = "golangci-lint"
private const val notificationGroupName = "Go linter notifications"
private val configFiles = arrayOf(".golangci.json", ".golangci.toml", ".golangci.yaml", ".golangci.yml")  // ordered by precedence
private val cmdQuote = if (SystemInfo.isWindows) '"' else '\''

val logger = Logger.getInstance("go-linter")
val notificationGroup = NotificationGroup.findRegisteredGroup(notificationGroupName) ?: NotificationGroup.balloonGroup(notificationGroupName)
val linterExecutableName = if (SystemInfo.isWindows) "$LinterName.exe" else LinterName
val executionDir: String = if (SystemInfo.isWindows) System.getenv("PUBLIC") else "/usr/local/bin"

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

fun fetchProcessOutput(process: Process): RunProcessResult {
    val outputConsumer = ByteArrayOutputStream()
    val outputThread = OutputReader.fetch(process.inputStream, outputConsumer)
    val errorConsumer = ByteArrayOutputStream()
    val errorThread = OutputReader.fetch(process.errorStream, errorConsumer)

    try {
        val returnCode = process.waitFor()
        errorThread.join()
        outputThread.join()

        return RunProcessResult(returnCode, outputConsumer.toString(), errorConsumer.toString())
    } catch (e: InterruptedException) {
        logger.error(e)
    }

    return RunProcessResult(-1, "", "")
}

fun getLatestReleaseMeta(httpClient: CloseableHttpClient): GithubRelease =
        Gson().fromJson(
                httpClient.execute(HttpGet("https://api.github.com/repos/golangci/golangci-lint/releases/latest")).use { response ->
                    CharStreams.toString(InputStreamReader(response.entity.content, Charset.defaultCharset()))
                },
                GithubRelease::class.java)

fun getPlatformSpecificBinName(meta: GithubRelease): String {
    val arch = System.getProperty("os.arch").let {
        when (it) {
            "x86" -> "386"
            "amd64", "x86_64" -> "amd64"
            else -> throw Exception("Unknown system arch: $it")
        }
    }

    val postFix = when (OS) {
        "windows" -> "zip"
        "linux", "darwin" -> "tar.gz"
        else -> throw Exception("Unknown system type: $OS")
    }
    return "$LinterName-${meta.name.substring(1)}-$OS-$arch.$postFix"
}

fun fetchLatestGoLinter(setText: (String) -> Unit, setFraction: (Double) -> Unit, cancelled: () -> Boolean): String {
    HttpClientBuilder.create().disableContentCompression().build().use { httpClient ->
        setText("Getting latest release meta")
        val latest = getLatestReleaseMeta(httpClient)

        setFraction(0.2)
        if (cancelled()) return ""

        val decompressFun: (String, String, (Double) -> Unit, () -> Boolean) -> Unit
        val tmpDir: String
        val toFile: String

        when (OS) {
            "windows" -> {
                decompressFun = ::unzip
                tmpDir = System.getenv("TEMP")
                toFile = "$executionDir\\$linterExecutableName"
            }
            "linux", "darwin" -> {
                decompressFun = ::untarball
                tmpDir = "/tmp"
                toFile = "$executionDir/$linterExecutableName"
            }
            else -> throw Exception("Unknown system type: $OS")
        }

        // "golangci-lint-1.23.3-darwin-amd64.tar.gz"
        val binaryFileName = getPlatformSpecificBinName(latest)
        val asset = latest.assets.single { it.name == binaryFileName }
        // "/tmp/golangci-lint-1.23.3-darwin-amd64.tar.gz"
        val tmp = Paths.get(tmpDir, binaryFileName).toString()

        setText("Downloading $binaryFileName")
        httpClient.execute(HttpGet(asset.browserDownloadUrl)).use { response ->
            copy(response.entity.content, tmp, asset.size.toLong(), { f -> setFraction(0.2 + 0.6 * f) }, cancelled)
        }

        setText("Decompressing to $toFile")
        decompressFun(tmp, toFile, { f -> setFraction(0.8 + 0.2 * f) }, cancelled)
        File(tmp).delete()

        if (File(toFile).let { !it.canExecute() && !it.setExecutable(true) }) {
            throw Exception("Permission denied to execute $toFile")
        }

        return toFile
    }
}

private fun unzip(file: String, to: String, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
    ZipInputStream(FileInputStream(file)).use { zis ->
        var zipEntry = zis.nextEntry
        while (zipEntry != null) {
            if (cancelled()) return
            if (zipEntry.name.endsWith(linterExecutableName)) {
                // sadly zip size is always -1 (unknown), based on experience we assume it's 21MB
                copy(zis, to, 22020096, setFraction, cancelled)
                zis.closeEntry()
                return
            }
            zipEntry = zis.nextEntry
        }

        zis.closeEntry()
    }

    throw FileNotFoundException(LinterName)
}

private fun untarball(file: String, to: String, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
    TarArchiveInputStream(GZIPInputStream(FileInputStream(file))).use { tis ->
        var tarEntry = tis.nextTarEntry
        while (tarEntry != null) {
            if (cancelled()) return
            if (tarEntry.name.endsWith(linterExecutableName)) {
                copy(tis, to, tarEntry.size, setFraction, cancelled)
                return
            }
            tarEntry = tis.nextTarEntry
        }
    }

    throw FileNotFoundException(LinterName)
}

// nio should be more efficient, but let's show some progress to make programmer happy
private fun copy(input: InputStream, to: String, totalSize: Long, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
    FileOutputStream(to).use { fos ->
        var sum = 0.0
        var len: Int
        val data = ByteArray(20 * 1024)

        while (true) {
            if (cancelled()) return

            len = input.read(data)
            if (len == -1) break
            fos.write(data, 0, len)
            sum += len
            setFraction(minOf(sum / totalSize, 1.0))
        }
    }
}

fun buildCommand(module: String, parameters: List<String>, envs: Map<String, String>): String =
        StringBuilder().apply {
            this.append("cd $module && ")     // open into working dir
            if (SystemInfo.isWindows) {
                for ((k, v) in envs)
                    this.append("set \"$k=$v\" && ")
            } else {
                for ((k, v) in envs)                        // set env
                    this.append("export $k=$v && ")
            }
            this.append(parameters.joinToString(" "){ "$cmdQuote$it$cmdQuote" })
        }.toString()

fun getGolangCiVersion(path: String): Optional<String> {
    if (File(GoLinterConfig.goLinterExe).canExecute()) {
        val result = GolangCiOutputParser.runProcess(listOf(path, "version", "--format", "json"), null, mapOf())
        if (result.returnCode == 0) {
            try {
                return Optional.of(Gson().fromJson(result.stderr, GolangciLintVersion::class.java).version)
            } catch (e: JsonSyntaxException) {
                // ignore
            }
        }
    }

    return Optional.empty()
}

private val OS: String by lazy {
    when {
        SystemInfo.isWindows -> "windows"
        SystemInfo.isLinux -> "linux"
        SystemInfo.isMac -> "darwin"
        else -> throw Exception("Unknown system type: ${SystemInfo.OS_NAME}")
    }
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