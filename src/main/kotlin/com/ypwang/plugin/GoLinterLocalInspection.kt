package com.ypwang.plugin

import com.goide.configuration.GoSdkConfigurable
import com.goide.project.GoProjectLibrariesService
import com.goide.psi.GoFile
import com.goide.sdk.GoSdkService
import com.google.gson.Gson
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.ypwang.plugin.form.GoLinterSettings
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.LintReport
import com.ypwang.plugin.model.RunProcessResult
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

class GoLinterLocalInspection : LocalInspectionTool() {
    companion object {
        private const val ErrorTitle = "Go linter running error"
        private const val notificationFrequencyCap = 60 * 1000L

        fun findCustomConfigInPath(path: String?): String {
            val varPath: String? = path
            if (varPath != null) {
                var cur: Path? = Paths.get(varPath)
                while (cur != null && cur.toFile().isDirectory) {
                    for (s in arrayOf(".golangci.json", ".golangci.toml", ".golangci.yml")) {
                        val f = cur.resolve(s).toFile()
                        if (f.exists() && f.isFile) { // found an valid config file
                            return f.path
                        }
                    }
                    cur = cur.parent
                }
            }

            return ""
        }
    }

    private class GoLinterWorkLoad(val runningPath: String, val processParameters: List<String>, val env: Map<String, String>) {
        val mutex = ReentrantLock()
        val condition: Condition = mutex.newCondition()
        var result: RunProcessResult? = null
    }

    private val systemPath = System.getenv("PATH")
    private val systemGoPath = System.getenv("GOPATH")      // immutable in current idea process
    private var showError = true
    private val notificationLastTime = AtomicLong(-1L)

    // consumer queue
    private val mutex = ReentrantLock()
    private val condition: Condition = mutex.newCondition()
    private val workLoads = sortedMapOf<String, GoLinterWorkLoad>()

    // cache module <> (timestamp, issues)
    private val cache = mutableMapOf<String, Pair<Long, List<LintIssue>?>>()

    init {
        // a singleton thread to execute go-linter, to avoid multiple instances drain out CPU
        Thread {
            while (true) {
                mutex.lock()
                if (workLoads.isEmpty())
                    condition.await()

                // pop LIFO
                val key = workLoads.lastKey()
                val head = workLoads[key]!!
                workLoads.remove(key)
                mutex.unlock()

                // executing
                head.result = fetchProcessOutput(ProcessBuilder(head.processParameters).apply {
                    val curEnv = this.environment()
                    head.env.forEach { kv -> curEnv[kv.key] = kv.value }
                    this.directory(File(head.runningPath))
                }.start())
                head.mutex.lock()
                head.condition.signal()
                head.mutex.unlock()
            }
        }.start()
    }

    private var customConfigFound: Boolean = false
    private var customConfigLastCheckTime = Long.MIN_VALUE

    private fun customConfigDetected(project: Project): Boolean =
        // cache the result max 10s
        System.currentTimeMillis().let {
            if (customConfigLastCheckTime + 10000 < it) {
                customConfigFound = findCustomConfigInPath(project.basePath).isNotEmpty()
                customConfigLastCheckTime = it
            }

            customConfigFound
        }

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        fun matchAndShow(issues: List<LintIssue>, matchName: String): Array<ProblemDescriptor>? {
            val rst = mutableListOf<ProblemDescriptor>()

            val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
            for (issue in issues.filter { it.Pos.Filename == matchName }) {
                if (issue.Pos.Line > document.lineCount) continue
                val lineNumber = issue.Pos.Line - 1
                var lineStart = document.getLineStartOffset(lineNumber)
                val lineEnd = document.getLineEndOffset(lineNumber)
                if (issue.SourceLines.firstOrNull() != document.getText(TextRange.create(lineStart, lineEnd)))
                    // Text not match, file is modified
                    break

                lineStart += issue.Pos.Column
                if (issue.Pos.Column > 0) lineStart--       // hack
                if (lineStart >= lineEnd) break

                rst.add(manager.createProblemDescriptor(
                        file,
                        TextRange.create(lineStart, lineEnd),
                        "${issue.Text} (${issue.FromLinter})",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        isOnTheFly
                ))
            }

            return rst.toTypedArray()
        }

        if (!File(GoLinterConfig.goLinterExe).canExecute()/* no linter executable */ || file !is GoFile) return null

        val absolutePath = Paths.get(file.virtualFile.path)     // file's absolute path
        val module = absolutePath.parent.toString()             // file's relative path to running dir
        val matchName = absolutePath.fileName.toString()        // file name

        run {
            var issues: List<LintIssue>? = null
            val lastModifyTimestamp = absolutePath.toFile().lastModified()
            // see if cached
            synchronized(cache) {
                // cached result is newer than both last config saved time and this file's last modified time
                if (module in cache && GoLinterSettings.getLastSavedTime() < cache[module]!!.first && lastModifyTimestamp < cache[module]!!.first) {
                    issues = cache[module]!!.second
                }
            }

            if (issues != null) {
                return matchAndShow(issues!!, matchName)
            }
        }

        // cache not found or outdated
        // ====================================================================================================================================

        // try best to get GOPATH, as GoLand or Intellij's go plugin have to know the correct 'GOPATH' for inspections,
        // ful GOPATH should be: Global GOPATH + IDE project GOPATH
        // IDE's take precedence
        val goPluginSettings = GoProjectLibrariesService.getInstance(manager.project)
        val goPaths = goPluginSettings.state.urls.map { Paths.get(VirtualFileManager.extractPath(it)) }.joinToString(File.pathSeparator) +
                (if (goPluginSettings.isUseGoPathFromSystemEnvironment && systemGoPath != null) systemGoPath + File.pathSeparator else "")

        val goExecutable = GoSdkService.getInstance(manager.project).getSdk(null).goExecutablePath
        var envPath = systemPath
        if (goExecutable != null) {
            val goBin = Paths.get(goExecutable).parent.toString()
            if (!envPath.contains(goBin))
                envPath = "$goBin${File.pathSeparator}$envPath"
        }

        // build parameters
        val parameters = mutableListOf(GoLinterConfig.goLinterExe, "run", "--out-format", "json")
        val provides = mutableSetOf<String>()

        if (GoLinterConfig.customOptions.isEmpty()) {
            parameters.add(GoLinterConfig.customOptions)
            provides.addAll(GoLinterConfig.customOptions.split(" "))
        }

        // don't use to much CPU
        if (!provides.contains("--concurrency")) {
            parameters.add("--concurrency")
            parameters.add(maxOf(1, (Runtime.getRuntime().availableProcessors() + 3) / 4).toString())   // at least 1 thread
        }

        if (!provides.contains("--max-issues-per-linter")) {
            parameters.add("--max-issues-per-linter")
            parameters.add("0")
        }

        if (!provides.contains("--max-same-issues")) {
            parameters.add("--max-same-issues")
            parameters.add("0")
        }

        // didn't find config in project root, nor the user selected use config file
        if ((!customConfigDetected(manager.project) || !GoLinterConfig.useConfigFile) && GoLinterConfig.enabledLinters != null) {
            parameters.add("--disable-all")
            parameters.add("-E")
            parameters.add(GoLinterConfig.enabledLinters!!.joinToString(",") { it.split(' ').first() })
        }
        parameters.add(".")

        val workLoad = GoLinterWorkLoad(module, parameters, mapOf("PATH" to envPath, "GOPATH" to goPaths))
        workLoad.mutex.lock()

        mutex.lock()
        val that = workLoads[module]
        if (that != null) {
            // newer is better, preempt old one
            workLoads.remove(module)
            that.mutex.lock()
            that.condition.signal()
            that.mutex.unlock()
        }

        workLoads[module] = workLoad
        condition.signal()
        mutex.unlock()

        // wait for worker done the job or been preempted
        workLoad.condition.await()
        workLoad.mutex.unlock()

        val now = System.currentTimeMillis()
        if (workLoad.result != null) {
            val processResult = workLoad.result!!
            when (processResult.returnCode) {
                // 0: no hint found; 1: hint found
                0, 1 -> {
                    val parsed = Gson().fromJson(processResult.stdout, LintReport::class.java).Issues
                    synchronized(cache) {
                        cache[module] = now to parsed
                    }

                    return parsed?.let { matchAndShow(it, matchName) }
                }
                // run error
                else -> {
                    logger.warn("Run error: ${processResult.stderr}. Usually it's caused by wrongly configured parameters or corrupted with config file.")

                    // freq cap 1min
                    if (showError && (notificationLastTime.get() + notificationFrequencyCap) < now) {
                        val notification = when {
                            processResult.stderr.contains("Can't read config") ->
                                notificationGroup.createNotification(
                                        ErrorTitle,
                                        "invalid format of config file",
                                        NotificationType.ERROR,
                                        null as NotificationListener?).apply {
                                    // find the config file
                                    val configFilePath = findCustomConfigInPath(module)
                                    val configFile = File(configFilePath)
                                    if (configFile.exists()) {
                                        this.addAction(NotificationAction.createSimple("Open ${configFile.name}") {
                                            OpenFileDescriptor(manager.project, LocalFileSystem.getInstance().findFileByIoFile(configFile)!!).navigate(true)
                                            this.expire()
                                        })
                                    }
                                }
                            processResult.stderr.contains("all linters were disabled, but no one linter was enabled") ->
                                notificationGroup.createNotification(
                                        ErrorTitle,
                                        "must enable at least one linter",
                                        NotificationType.ERROR,
                                        null as NotificationListener?).apply {
                                    this.addAction(NotificationAction.createSimple("Configure") {
                                        ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoLinterSettings(manager.project))
                                        this.expire()
                                    })
                                }
                            processResult.stderr.contains("\\\"go\\\": executable file not found in \$PATH") ->
                                notificationGroup.createNotification(
                                        ErrorTitle,
                                        "'GOROOT' must be set",
                                        NotificationType.ERROR,
                                        null as NotificationListener?).apply {
                                    this.addAction(NotificationAction.createSimple("Setup GOROOT") {
                                        ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoSdkConfigurable(manager.project, true))
                                        this.expire()
                                    })
                                }
                            processResult.stderr.contains("error computing diff") ->
                                notificationGroup.createNotification(
                                        ErrorTitle,
                                        "diff is needed for running gofmt/goimports. Either put GNU diff & GNU LibIconv binary in PATH, or disable gofmt/goimports.",
                                        NotificationType.ERROR,
                                        null as NotificationListener?).apply {
                                    this.addAction(NotificationAction.createSimple("Configure") {
                                        ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoLinterSettings(manager.project))
                                        this.expire()
                                    })
                                }
                            else ->
                                notificationGroup.createNotification(
                                        ErrorTitle,
                                        "Possibly invalid config or syntax error",
                                        NotificationType.ERROR,
                                        null as NotificationListener?).apply {
                                    this.addAction(NotificationAction.createSimple("Configure") {
                                        ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoLinterSettings(manager.project))
                                        this.expire()
                                    })
                                }
                        }

                        notification.addAction(NotificationAction.createSimple("Do not show again") {
                            showError = false
                            notification.expire()
                        })

                        notification.notify(manager.project)
                        notificationLastTime.set(now)
                    }
                }
            }
        }

        // or skip current run
        return null
    }
}