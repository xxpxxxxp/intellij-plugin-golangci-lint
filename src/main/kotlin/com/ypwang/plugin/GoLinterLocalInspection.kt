package com.ypwang.plugin

import com.goide.configuration.GoSdkConfigurable
import com.goide.project.GoModuleSettings
import com.goide.psi.GoFile
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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.jetbrains.rd.util.first
import com.twelvemonkeys.util.LRUMap
import com.ypwang.plugin.form.GoLinterSettings
import com.ypwang.plugin.handler.DefaultHandler
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.RunProcessResult
import java.io.File
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.LinkedHashMap
import kotlin.concurrent.withLock

data class Tuple4<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)

private class GoLinterWorkLoad {
    val executionMutex = ReentrantLock()
    val executionCondition: Condition = executionMutex.newCondition()
    val broadcastMutex = ReentrantLock()
    val broadcastCondition: Condition = broadcastMutex.newCondition()
    var result: RunProcessResult? = null
}

class GoLinterLocalInspection : LocalInspectionTool(), UnfairLocalInspectionTool {
    companion object {
        private const val ErrorTitle = "Go linter running error"
        private const val notificationFrequencyCap = 60 * 1000L

        // Intellij create different instances for multiple projects
        // limit golangci-lint concurrency, save CPU resource
        // consumer queue
        private val executionLock = AtomicBoolean(false)
        private val workloadsLock = ReentrantLock()
        private val workLoads = LinkedHashMap<String, GoLinterWorkLoad>()
    }

    // reduce error show freq
    private var showError = true
    private var notificationLastTime = AtomicLong(-1)

    // cache config search to save time
    private var customConfig: Optional<String> = Optional.empty()
    private var customConfigLastCheckTime = AtomicLong(-1)
    private fun customConfigDetected(path: String): Optional<String> =
            // cache the result max 10s
            System.currentTimeMillis().let {
                if (customConfigLastCheckTime.get() + 60000 < it || customConfigLastCheckTime.get() < GoLinterSettings.getLastSavedTime()) {
                    customConfig = findCustomConfigInPath(path)
                    customConfigLastCheckTime.set(it)
                }

                customConfig
            }

    // cache module <> (timestamp, issues)
    /** Intellij share memory between instances
     *  If multiple projects are opened, this plugin will cache a lot issues and eventually eat up all memory, slow down the IDE
     *  use LRU map to reduce memory usage
     */
    private val cache = LRUMap<String, Pair<Long, List<LintIssue>?>>(13)

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is GoFile || !File(GoLinterConfig.goLinterExe).canExecute()/* no linter executable */) return null

        val project = manager.project
        val absolutePath = Paths.get(file.virtualFile.path)     // file's absolute path
        val (runningPath, relativePath, cachePath, matchName) =
            if (GoLinterConfig.enableCustomProjectDir) {
                // fallback to project base path
                val projectPath = Paths.get(GoLinterConfig.customProjectDir.orElse(project.basePath!!))

                if (!absolutePath.startsWith(projectPath))
                    // file is not in current Go project, skip
                    return null

                val relative = projectPath.relativize(absolutePath.parent).toString()        // file's relative path to running dir
                val fileName = projectPath.relativize(absolutePath).toString()               // file name
                Tuple4(
                    projectPath.toString(),
                    relative,
                    relative,
                    fileName
                )
            } else {
                val module = absolutePath.parent.toString()             // file's dir
                val fileName = absolutePath.fileName.toString()         // file name
                Tuple4(
                    module,
                    ".",
                    module,
                    fileName
                )
            }

        run {
            // see if cached
            val issueWithTTL = synchronized(cache) {
                cache[cachePath]
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
        return try {
            val params = buildParameters(file, project, relativePath)
            val issues = runAndProcessResult(project, runningPath, params, mapOf("PATH" to getSystemPath(project), "GOPATH" to getGoPath(project)))
            synchronized(cache) {
                cache[cachePath] = System.currentTimeMillis() to issues
            }

            matchAndShow(file, manager, isOnTheFly, issues, matchName)
        } catch (e: Exception) {
            null
        }
    }

    private fun isSaved(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile
        val fileEditorManager = FileEditorManager.getInstance(file.project)

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

    private fun buildParameters(file: PsiFile, project: Project, sub: String): List<String> {
        val parameters = mutableListOf(
                GoLinterConfig.goLinterExe,
                "run", "--out-format", "json", "--allow-parallel-runners",
                // don't use to much CPU. Runtime should have at least 1 available processor, right?
                "-j", ((Runtime.getRuntime().availableProcessors() + 3) / 4).toString(),
                // no issue limit
                "--max-issues-per-linter", "0", "--max-same-issues", "0"
        )

        customConfigDetected(GoLinterConfig.customProjectDir.orElse(project.basePath!!)).ifPresentOrElse(
                {
                    parameters.add("-c")
                    parameters.add(it)
                },
                {
                    // use default linters
                    val enabledLinters = GoLinterConfig.enabledLinters
                    if (enabledLinters != null) {
                        // no linter is selected, skip run
                        if (enabledLinters.isEmpty())
                            throw Exception("all linters disabled")

                        parameters.add("--disable-all")
                        parameters.add("-E")
                        parameters.add(enabledLinters.joinToString(","))
                    }
                }
        )

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

        parameters.add(sub)
        return parameters
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
    private fun runAndProcessResult(project: Project, runningPath: String, parameters: List<String>, env: Map<String, String>): List<LintIssue> {
        val processResult = execute(runningPath, parameters, env)
        when (processResult.returnCode) {
            // 0: no hint found; 1: hint found
            0, 1 -> return GolangCiOutputParser.parseIssues(processResult)
            // run error
            else -> {
                logger.warn("Run error: ${processResult.stderr}. Please make sure the project has no syntax error.")

                val now = System.currentTimeMillis()
                // freq cap 1min
                if (showError && (notificationLastTime.get() + notificationFrequencyCap) < now) {
                    logger.warn("Debug command: ${buildCommand(runningPath, parameters, env)}")

                    val notification = when {
                        // syntax error or package not found, fix that first
                        processResult.stderr.contains("analysis skipped: errors in package") || processResult.stderr.contains("typechecking error") ->
                            throw Exception("syntax error")
                        processResult.stderr.contains("Can't read config") ->
                            notificationGroup.createNotification(
                                    ErrorTitle,
                                    "invalid format of config file",
                                    NotificationType.ERROR,
                                    null as NotificationListener?).apply {
                                // find the config file
                                findCustomConfigInPath(project.basePath!!).ifPresent {
                                    val configFile = File(it)
                                    if (configFile.exists()) {
                                        this.addAction(NotificationAction.createSimple("Open ${configFile.name}") {
                                            OpenFileDescriptor(project, LocalFileSystem.getInstance().findFileByIoFile(configFile)!!).navigate(true)
                                            this.expire()
                                        })
                                    }
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
                                    "Please make sure there's no syntax error, then check if any config error",
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

                // or skip current run
                throw Exception("run failed")
            }
        }
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
                val (quickFix, range) = handler.suggestFix(file, document, issue, lineNumber)

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
