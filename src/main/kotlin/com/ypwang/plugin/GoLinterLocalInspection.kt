package com.ypwang.plugin

import com.goide.configuration.GoSdkConfigurable
import com.goide.project.GoApplicationLibrariesService
import com.goide.project.GoModuleSettings
import com.goide.project.GoProjectLibrariesService
import com.goide.psi.GoFile
import com.goide.sdk.GoSdkService
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.UnfairLocalInspectionTool
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.jetbrains.rd.util.first
import com.ypwang.plugin.form.GoLinterSettings
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.RunProcessResult
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GoLinterLocalInspection : LocalInspectionTool(), UnfairLocalInspectionTool {
    companion object {
        private const val ErrorTitle = "Go linter running error"
        private const val notificationFrequencyCap = 60 * 1000L

        private val systemPath = System.getenv("PATH")
        private val systemGoPath = System.getenv("GOPATH")      // immutable in current idea process

        fun findCustomConfigInPath(path: String?): String {
            val varPath: String? = path
            if (varPath != null) {
                var cur: Path? = Paths.get(varPath)
                while (cur != null && cur.toFile().isDirectory) {
                    for (s in arrayOf(".golangci.json", ".golangci.toml", ".golangci.yaml", ".golangci.yml")) { // ordered by precedence
                        val f = cur.resolve(s).toFile()
                        if (f.exists() && f.isFile) { // found a valid config file
                            return f.path
                        }
                    }
                    cur = cur.parent
                }
            }

            return ""
        }

        private fun isSaved(file: PsiFile): Boolean {
            val virtualFile = file.virtualFile
            val fileEditorManager = FileEditorManager.getInstance(file.project)
            if (!fileEditorManager.isFileOpen(virtualFile)) return true     // no editor opened, so data should be saved

            var saved = true
            val done = AtomicBoolean(false)       // here we use atomic variable as a spinlock

            ApplicationManager.getApplication().invokeLater {
                // ideally there should be 1 editor, unless in split view
                for (editor in fileEditorManager.getEditors(virtualFile)) {
                    if (editor.isModified) {
                        saved = false
                        break
                    }
                }
                done.set(true)
            }

            val now = System.currentTimeMillis()
            while (!done.compareAndSet(true, false)) {
                // as a last resort, break the loop on timeout
                if (System.currentTimeMillis() - now > 1000) {
                    // may hang for 1s, hope that never happen
                    logger.info("Cannot get confirmation from dispatch thread, break the loop")
                    return false
                }
            }

            return saved
        }
    }

    private class GoLinterWorkLoad {
        val executionMutex = ReentrantLock()
        val executionCondition: Condition = executionMutex.newCondition()
        val broadcastMutex = ReentrantLock()
        val broadcastCondition: Condition = broadcastMutex.newCondition()
        var result: RunProcessResult? = null
    }

    private var showError = true
    private val notificationLastTime = AtomicLong(-1L)

    // consumer queue
    private val executionLock = AtomicBoolean(false)
    private val workloadsLock = ReentrantLock()
    private val workLoads = LinkedHashMap<String, GoLinterWorkLoad>()

    // cache module <> (timestamp, issues)
    private val cache = mutableMapOf<String, Pair<Long, List<LintIssue>?>>()

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
        if (!File(GoLinterConfig.goLinterExe).canExecute()/* no linter executable */ || file !is GoFile) return null

        val absolutePath = Paths.get(file.virtualFile.path)     // file's absolute path
        val module = absolutePath.parent.toString()             // file's relative path to running dir
        val matchName = absolutePath.fileName.toString()        // file name

        run {
            // see if cached
            val issueWithTTL = synchronized(cache) {
                cache[module]
            }

            if (
                    // don't run linter when hot editing, as that will cause typing lagged
                    // while if we have previous result, it's better than nothing to return those results
                    // issues not in dirty zone could still be useful
                    !isSaved(file) ||
                    // cached result is newer than both last config saved time and this file's last modified time
                    (issueWithTTL != null && file.virtualFile.timeStamp < issueWithTTL.first && GoLinterSettings.getLastSavedTime() < issueWithTTL.first))
                return issueWithTTL?.second?.let { matchAndShow(file, manager, isOnTheFly, it, matchName) }
        }

        // cache not found or outdated
        // ====================================================================================================================================
        val project = manager.project
        val params = buildParameters(file, project) ?: return null
        val issues = runAndProcessResult(project, module, params, buildEnvironment(project)) ?: return null
        synchronized(cache) {
            cache[module] = System.currentTimeMillis() to issues
        }
        return matchAndShow(file, manager, isOnTheFly, issues, matchName)
    }

    private fun buildParameters(file: PsiFile, project: Project): List<String>? {
        val parameters = mutableListOf(GoLinterConfig.goLinterExe, "run", "--out-format", "json")
        val provides = mutableSetOf<String>()

        if (GoLinterConfig.useCustomOptions && GoLinterConfig.customOptions.isNotEmpty()) {
            val breaks = GoLinterConfig.customOptions.split(" ")
            parameters.addAll(breaks)
            provides.addAll(breaks)
        }

        if (!provides.contains("--build-tags")) {
            val module = ModuleUtilCore.findModuleForFile(file)
            if (module != null) {
                val buildTagsSettings = GoModuleSettings.getInstance(module).buildTargetSettings
                val default = "default"
                val buildTags = mutableListOf<String>().apply {
                    if (buildTagsSettings.arch != default) this.add(buildTagsSettings.arch)
                    if (buildTagsSettings.os != default) this.add(buildTagsSettings.os)
                    this.addAll(buildTagsSettings.customFlags)
                }

                if (buildTags.isNotEmpty()) {
                    parameters.add("--build-tags")
                    parameters.add(buildTags.joinToString(","))
                }
            }
        }

        // don't use to much CPU
        if (!provides.contains("--concurrency")) {
            parameters.add("--concurrency")
            // runtime should have at least 1 available processor, right?
            parameters.add(((Runtime.getRuntime().availableProcessors() + 3) / 4).toString())
        }

        if (!provides.contains("--max-issues-per-linter")) {
            parameters.add("--max-issues-per-linter")
            parameters.add("0")
        }

        if (!provides.contains("--max-same-issues")) {
            parameters.add("--max-same-issues")
            parameters.add("0")
        }

        // TODO: this flag is deprecating, while currently there's no better way
        if (!provides.contains("--maligned.suggest-new"))
            parameters.add("--maligned.suggest-new")

        // didn't find config in project root, nor the user selected use config file
        if (!customConfigDetected(project)) {
            val enabledLinters = GoLinterConfig.enabledLinters
            if (enabledLinters != null) {
                // no linter is selected, skip run
                if (enabledLinters.isEmpty())
                    return null

                parameters.add("--disable-all")
                parameters.add("-E")
                parameters.add(enabledLinters.joinToString(","))
            }
        }
        parameters.add(".")

        return parameters
    }

    private fun buildEnvironment(project: Project): Map<String, String> {
        // try best to get GOPATH, as GoLand or Intellij's go plugin have to know the correct 'GOPATH' for inspections,
        // full GOPATH should be: IDE project GOPATH + Global GOPATH
        val goPluginSettings = GoProjectLibrariesService.getInstance(project)
        val goPaths = goPluginSettings.libraryRootUrls.map { Paths.get(VirtualFileManager.extractPath(it)).toString() }.toMutableList().apply {
            if (goPluginSettings.isUseGoPathFromSystemEnvironment) {
                this.addAll(GoApplicationLibrariesService.getInstance().libraryRootUrls.map { p -> Paths.get(VirtualFileManager.extractPath(p)).toString() })
                if (systemGoPath != null)
                    this.add(systemGoPath)
            }
        }.joinToString(File.pathSeparator)

        var envPath = systemPath
        val goExecutable = GoSdkService.getInstance(project).getSdk(null).goExecutablePath
        if (goExecutable != null) {
            // for Mac users: OSX is using different PATH for terminal & GUI, Intellij seems cannot inherit '/usr/local/bin' as PATH
            // that cause problem because golangci-lint depends on `go env` to discover GOPATH (I don't know why they do this)
            // add Go plugin's SDK path to PATH if needed in order to make golangci-lint happy
            val goBin = Paths.get(goExecutable).parent.toString()
            if (!envPath.contains(goBin))
                envPath = "$goBin${File.pathSeparator}$envPath"
        }

        return mapOf("PATH" to envPath, "GOPATH" to goPaths)
    }

    // executionLock must be hold during the whole time of execution
    // wake up backlog thread if there's any, or release executionLock
    private fun executeAndWakeBacklogThread(runningPath: String, parameters: List<String>, env: Map<String, String>): RunProcessResult {
        val result = GolangCiOutputParser.runProcess(parameters, runningPath, env)

        val workload = workloadsLock.withLock {
            if (workLoads.isNotEmpty()) {
                val kv = workLoads.first()
                workLoads.remove(kv.key)
                kv.value
            } else {
                // nobody waiting in backlog, release executionLock
                executionLock.set(false)
                return result
            }
        }

        // then 'executionLock' must be holding, turn over executionLock
        workload.executionMutex.withLock {
            workload.executionCondition.signal()
        }

        return result
    }

    private fun execute(runningPath: String, parameters: List<String>, env: Map<String, String>): RunProcessResult {
        // main execution logic: 1. FIFO; 2. De-dup in backlog
        if (executionLock.compareAndSet(false, true))
            // own the execution lock, run immediately
            return executeAndWakeBacklogThread(runningPath, parameters, env)

        // had to wait, add to backlog
        val (foundInBacklog, workload) = workloadsLock.withLock {
            // double check
            if (executionLock.compareAndSet(false, true))
                // working thread released executionLock, so there's no backlog now, run immediately
                return executeAndWakeBacklogThread(runningPath, parameters, env)

            var found = true
            var wl = workLoads[runningPath]
            if (wl == null) {
                found = false
                wl = GoLinterWorkLoad()
                workLoads[runningPath] = wl
            }

            if (found) wl.broadcastMutex.lock()     // wait on others notify me
            else wl.executionMutex.lock()           // wait on others release execution lock

            found to wl
        }

        if (foundInBacklog) {
            // waiting for others finish the job
            workload.broadcastCondition.await()
            workload.broadcastMutex.unlock()
        } else {
            workload.executionCondition.await()
            workload.executionMutex.unlock()
            // executionLock guaranteed
            workload.result = executeAndWakeBacklogThread(runningPath, parameters, env)
            // wake up backlog threads listen on me
            workload.broadcastMutex.lock()
            workload.broadcastCondition.signalAll()
            workload.broadcastMutex.unlock()
        }

        return workload.result!!
    }

    // return issues (might be empty) if run succeed
    // return null if run failed
    private fun runAndProcessResult(project: Project, module: String, parameters: List<String>, env: Map<String, String>): List<LintIssue>? {
        val processResult = execute(module, parameters, env)
        when (processResult.returnCode) {
            // 0: no hint found; 1: hint found
            0, 1 -> return GolangCiOutputParser.parseIssues(processResult)
            // run error
            else -> {
                logger.warn("Run error: ${processResult.stderr}. Usually it's caused by wrongly configured parameters or corrupted with config file.")

                val now = System.currentTimeMillis()
                // freq cap 1min
                if (showError && (notificationLastTime.get() + notificationFrequencyCap) < now) {
                    logger.warn("Debug command: ${ buildCommand(module, parameters, env) }")

                    val notification = when {
                        processResult.stderr.contains("analysis skipped: errors in package") || processResult.stderr.contains("typechecking error") ->
                            // syntax error or package not found, programmer should fix that first
                            return null
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
                                        OpenFileDescriptor(project, LocalFileSystem.getInstance().findFileByIoFile(configFile)!!).navigate(true)
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
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
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
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoSdkConfigurable(project, true))
                                    this.expire()
                                })
                            }
                        processResult.stderr.contains("error computing diff") ->
                            notificationGroup.createNotification(
                                    ErrorTitle,
                                    "diff is needed for running gofmt/goimports/gci. Either put <a href=\"http://ftp.gnu.org/gnu/diffutils/\">GNU diff</a> & <a href=\"https://ftp.gnu.org/pub/gnu/libiconv/\">GNU LibIconv</a> binary in PATH, or disable gofmt/goimports.",
                                    NotificationType.ERROR,
                                    NotificationListener.URL_OPENING_LISTENER).apply {
                                this.addAction(NotificationAction.createSimple("Configure") {
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
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
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
                                    this.expire()
                                })
                            }
                    }

                    notification.addAction(NotificationAction.createSimple("Do not show again") {
                        showError = false
                        notification.expire()
                    })

                    notification.notify(project)
                    notificationLastTime.set(now)
                }
            }
        }

        // or skip current run
        return null
    }

    private fun matchAndShow(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean, issues: List<LintIssue>, matchName: String): Array<ProblemDescriptor>? {
        val document = PsiDocumentManager.getInstance(manager.project).getDocument(file) ?: return null
        var lineShift = -1      // linter reported line is 1-based
        var shiftCount = 0
        val beforeDirtyZone = mutableListOf<ProblemDescriptor>()
        val afterDirtyZone = mutableListOf<ProblemDescriptor>()
        // issues is already sorted by #line
        for (issue in issues.filter { it.Pos.Filename == matchName }) {
            var lineNumber = issue.Pos.Line + lineShift
            if (issue.SourceLines != null       // for 'unused', SourceLines is null, unable to determine line shift, just skip them
                    && lineNumber < document.lineCount
                    && issue.SourceLines.first() !=
                            document.getText(TextRange.create(document.getLineStartOffset(lineNumber), document.getLineEndOffset(lineNumber)))) {
                /** for a modification, line is added / changed / deleted
                 * which means, zone before / after dirty zone is not changed
                 * issues in clean zone may still useful
                 */
                // entering dirty zone
                afterDirtyZone.clear()             // previous match may be mistake in dirty zone
                if (shiftCount > 4) break          // avoid endless shifting
                var relocated = false
                // search for equal line before / after
                for (line in maxOf(0, lineNumber - 5 - shiftCount)..minOf(document.lineCount - 1, lineNumber + 5 + shiftCount)) {
                    if (line != lineNumber
                            && issue.SourceLines.first() ==
                            document.getText(TextRange.create(document.getLineStartOffset(line), document.getLineEndOffset(line)))) {
                        lineShift = line - issue.Pos.Line
                        relocated = true
                        break
                    }
                }

                shiftCount++
                // unable to locate the shift, text is not matched, so skip current issue
                if (!relocated) continue
                // because line shifted, re-calc pos
                lineNumber = issue.Pos.Line + lineShift
            }

            if (lineNumber >= document.lineCount) continue
            try {
                val handler = quickFixHandler.getOrDefault(issue.FromLinter, DefaultHandler)
                val (quickFix, range) = handler.suggestFix(issue.FromLinter, file, document, issue, lineNumber)

                val zone = if (shiftCount == 0) beforeDirtyZone else afterDirtyZone
                zone.add(manager.createProblemDescriptor(
                        file,
                        range,
                        handler.description(issue),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        isOnTheFly,
                        *quickFix
                ))
            } catch (_: Throwable) {
                // just ignore it
            }
        }

        beforeDirtyZone.addAll(afterDirtyZone)
        return beforeDirtyZone.toTypedArray()
    }
}
