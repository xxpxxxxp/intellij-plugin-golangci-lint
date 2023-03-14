package com.ypwang.plugin

import com.goide.configuration.GoSdkConfigurable
import com.goide.project.GoModuleSettings
import com.goide.psi.GoFile
import com.google.gson.Gson
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.containers.SLRUMap
import com.jetbrains.rd.util.first
import com.ypwang.plugin.form.GoLinterConfigurable
import com.ypwang.plugin.handler.DefaultHandler
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.LintReport
import com.ypwang.plugin.model.RunProcessResult
import com.ypwang.plugin.platform.Platform
import com.ypwang.plugin.platform.Platform.Companion.platformFactory
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private class GoLinterWorkLoad {
    val executionMutex = ReentrantLock()
    val executionCondition: Condition = executionMutex.newCondition()
    val broadcastMutex = ReentrantLock()
    val broadcastCondition: Condition = broadcastMutex.newCondition()
    var result: RunProcessResult? = null
}

class GoLinterExternalAnnotator : ExternalAnnotator<PsiFile, GoLinterExternalAnnotator.Result>() {
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
        // cache the result max 1min
        System.currentTimeMillis().let {
            if (customConfigLastCheckTime.get() + 60000 < it || customConfigLastCheckTime.get() < GoLinterConfigurable.getLastSavedTime()) {
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
    private val cache = SLRUMap<String, Pair<Long, List<LintIssue>>>(23, 19)

    data class Result(val matchName: String, val annotations: List<LintIssue>)

    override fun getPairedBatchInspectionShortName(): String = GoLinterLocalInspection.SHORT_NAME

    override fun collectInformation(file: PsiFile): PsiFile? =
        runReadAction {
            val project = file.project
            val platform = platformFactory(project)
            if (file is GoFile && file.isValid && file.virtualFile != null &&
                // valid linter executable or config file + linter in path
                (platform.canExecute(GoLinterSettings.getInstance(project).goLinterExe) ||
                        (customConfigDetected(project.basePath!!).isPresent && platform.defaultExecutable.isNotEmpty())))
                file
            else
                null
        }

    override fun doAnnotate(file: PsiFile): Result? {
        val project = file.project
        val settings = GoLinterSettings.getInstance(project)
        val absolutePath = Paths.get(file.virtualFile.path)     // file's absolute path

        // fallback to project base path
        val projectPath = Paths.get(settings.customProjectDir ?: project.basePath!!)
        if (!absolutePath.startsWith(projectPath))
            // file is not in current Go project, skip
            return null

        val platform = platformFactory(project)
        // runningPath:  the golangci-lint running dir
        // relativePath: the run target folder
        // matchName:    the file to match the output lint, to work with WSL, match name must be converted to platform specified
        val (runningPath, relativePath, matchName) =
            if (settings.enableCustomProjectDir) {
                Triple(
                    projectPath.toString(),
                    // the relative path of file's dir to running dir
                    platform.convertToPlatformPath(projectPath.relativize(absolutePath.parent).toString().ifBlank { "." }),
                    // the relative path of file to running dir
                    platform.convertToPlatformPath(projectPath.relativize(absolutePath).toString())
                )
            } else {
                Triple(
                    absolutePath.parent.toString(),     // absolute file dir
                    ".",
                    absolutePath.fileName.toString()    // relative file name
                )
            }

        // cachePath: the absolute path of file dir, as the cache key
        val cachePath = absolutePath.parent.toString()
        run {
            // see if cached
            val issueWithTTL = synchronized(cache) {
                cache.get(cachePath)
            }

            if (
                // don't run linter when file is not saved
                // while if we have previous result, it's better than nothing to return those results
                // issues not in dirty zone could still be useful
                !isSaved(file) ||
                // cached result is newer than both last config saved time and this file's last modified time
                (issueWithTTL != null && file.virtualFile.timeStamp < issueWithTTL.first && GoLinterConfigurable.getLastSavedTime() < issueWithTTL.first))
                return Result(matchName, issueWithTTL?.second ?: listOf())
        }

        // cache not found or outdated
        // ====================================================================================================================================
        return try {
            val issues = runAndProcessResult(
                project,
                platform,
                platform.toRunningOSPath(runningPath),
                buildParameters(file, project, platform, settings, relativePath),
                listOf(Const_Path, Const_GoPath, Const_GoModule),
                file.virtualFile.charset
            )
            synchronized(cache) {
                cache.put(cachePath, System.currentTimeMillis() to issues)
            }

            Result(matchName, issues)
        } catch (e: Exception) {
            null
        }
    }

    private class Anno (
        val description: String,
        val range: TextRange,
        val fixes: Array<IntentionAction>
    )

    override fun apply(file: PsiFile, annotationResult: Result, holder: AnnotationHolder) {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        var lineShift = -1      // linter reported line is 1-based
        var shiftCount = 0
        val beforeDirtyZone = mutableListOf<Anno>()
        val afterDirtyZone = mutableListOf<Anno>()
        // issues is already sorted by #line
        for (issue in annotationResult.annotations.filter { it.Pos.Filename == annotationResult.matchName }) {
            var lineNumber = issue.Pos.Line + lineShift
            if (lineNumber < document.lineCount &&
                issue.SourceLines != null &&       // for 'unused', SourceLines is null, unable to determine line shift, just skip them
                issue.SourceLines.first() != document.getText(
                    TextRange.create(document.getLineStartOffset(lineNumber), document.getLineEndOffset(lineNumber))
                )
            ) {
                /** for a modification, line is added / changed / deleted
                 * which means, zone before / after dirty zone is not changed
                 * issues in clean zone may still useful
                 */
                // entering dirty zone
                afterDirtyZone.clear()             // previous match may be mistake in dirty zone
                if (shiftCount > 3) break          // avoid endless shifting
                var relocated = false
                // search for equal line before / after
                for (line in maxOf(0, lineNumber - 5 - shiftCount)..minOf(document.lineCount - 1, lineNumber + 5 + shiftCount)) {
                    if (line != lineNumber
                        && issue.SourceLines.first() == document.getText(
                            TextRange.create(document.getLineStartOffset(line), document.getLineEndOffset(line))
                        )
                    ) {
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
                zone.add(Anno(handler.description(issue), range, quickFix))
            } catch (_: Throwable) {
                // just ignore it
            }
        }

        beforeDirtyZone.addAll(afterDirtyZone)
        beforeDirtyZone.forEach {
            val builder = holder.newAnnotation(HighlightSeverity.GENERIC_SERVER_ERROR_OR_WARNING, it.description)
                .range(it.range)

            for (fix in it.fixes) {
                builder.withFix(fix)
            }

            builder.create()
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

    private fun buildParameters(file: PsiFile, project: Project, platform: Platform, settings: GoLinterSettings, targetDir: String): List<String> {
        var exe = settings.goLinterExe
        if (exe.isEmpty())
            exe = platform.defaultExecutable

        val parameters = mutableListOf(
            platform.toRunningOSPath(exe), "run",
            "--out-format", "json",
            "--allow-parallel-runners",
            // control concurrency
            "-j", settings.concurrency.toString(),
            // fix exit code on issue
            "--issues-exit-code", "1",
            // no issue limit
            "--max-issues-per-linter", "0",
            "--max-same-issues", "0"
        )

        customConfigDetected(settings.customProjectDir ?: project.basePath!!)
            .or { Optional.ofNullable(settings.customConfigFile) }
            .ifPresentOrElse(
                    {
                        parameters.add("-c")
                        parameters.add(platform.toRunningOSPath(it))
                    },
                    {
                        parameters.add("--no-config")
                        // use default linters
                        if (settings.linterSelected) {
                            val enabledLinters = settings.enabledLinters
                            // no linter is selected, skip run
                            if (enabledLinters.isEmpty())
                                throw Exception("all linters disabled")

                            parameters.add("--disable-all")
                            parameters.add("-E")
                            parameters.add(enabledLinters.joinToString(","))
                        }
                    }
                )

        // add same build tags with those in Goland
        val module = ModuleUtilCore.findModuleForFile(file)
        if (module != null) {
            val buildTagsSettings = GoModuleSettings.getInstance(module).buildTargetSettings
            val default = "default"
            val buildTags = mutableListOf<String>()
            if (buildTagsSettings.arch != default)
                buildTags.add(buildTagsSettings.arch)
            if (buildTagsSettings.os != default)
                buildTags.add(buildTagsSettings.os)
            buildTags.addAll(buildTagsSettings.customFlags)

            if (buildTags.isNotEmpty()) {
                parameters.add("--build-tags")
                parameters.add(buildTags.joinToString(","))
            }
        }

        parameters.add(targetDir)
        return parameters
    }

    // executionLock must be hold during the whole time of execution
    // wake up backlog thread if there's any, or release executionLock
    private fun executeAndWakeBacklogThread(platform: Platform, runningPath: String, parameters: List<String>, vars: List<String>, encoding: Charset): RunProcessResult {
        val result = platform.runProcess(parameters, runningPath, vars, encoding)
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

    private fun execute(platform: Platform, runningPath: String, parameters: List<String>, vars: List<String>, encoding: Charset): RunProcessResult {
        // main execution logic: 1. FIFO; 2. De-dup in backlog
        if (executionLock.compareAndSet(false, true))
            // own the execution lock, run immediately
            return executeAndWakeBacklogThread(platform, runningPath, parameters, vars, encoding)

        // had to wait, add to backlog
        val (foundInBacklog, workload) = workloadsLock.withLock {
            // double check
            if (executionLock.compareAndSet(false, true))
                // working thread released executionLock, so there's no backlog now, run immediately
                return executeAndWakeBacklogThread(platform, runningPath, parameters, vars, encoding)

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
            workload.result = executeAndWakeBacklogThread(platform, runningPath, parameters, vars, encoding)
            // wake up backlog threads listen on me
            workload.broadcastMutex.lock()
            workload.broadcastCondition.signalAll()
            workload.broadcastMutex.unlock()
        }

        return workload.result!!
    }

    // return issues (might be empty) if run succeed
    // return null if run failed
    private fun runAndProcessResult(
        project: Project,
        platform: Platform,
        runningPath: String,
        parameters: List<String>,
        vars: List<String>,
        encoding: Charset
    ): List<LintIssue> {
        val processResult = execute(platform, runningPath, parameters, vars, encoding)
        when (processResult.returnCode) {
            // 0: no hint found; 1: hint found
            0, 1 ->
                return Gson().fromJson(processResult.stdout, LintReport::class.java)
                    .Issues
                    ?.sortedWith(compareBy({ issue -> issue.Pos.Filename }, { issue -> issue.Pos.Line }))
                    ?: listOf()
            // run error
            else -> {
                logger.warn("Run error: ${processResult.stderr}. Please make sure the project has no syntax error.")

                val now = System.currentTimeMillis()
                // freq cap 1min
                if (showError && (notificationLastTime.get() + notificationFrequencyCap) < now) {
                    logger.warn("Debug command: ${ platform.buildCommand(parameters, runningPath, vars) }")

                    val notification = when {
                        // syntax error or package not found, fix that first
                        processResult.stderr.contains("analysis skipped: errors in package") || processResult.stderr.contains("typechecking error") ->
                            throw Exception("syntax error")
                        processResult.stderr.contains("Can't read config") ->
                            notificationGroup.createNotification(
                                    ErrorTitle,
                                    "Invalid format of config file",
                                    NotificationType.ERROR).apply {
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
                                    "Must enable at least one linter",
                                    NotificationType.ERROR).apply {
                                this.addAction(NotificationAction.createSimple("Configure") {
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterConfigurable(project))
                                    this.expire()
                                })
                            }
                        processResult.stderr.contains("\\\"go\\\": executable file not found in \$PATH") ->
                            notificationGroup.createNotification(
                                    ErrorTitle,
                                    "'GOROOT' must be set",
                                    NotificationType.ERROR).apply {
                                this.addAction(NotificationAction.createSimple("Setup GOROOT") {
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoSdkConfigurable(project, true))
                                    this.expire()
                                })
                            }
                        processResult.stderr.contains("error computing diff") ->
                            notificationGroup.createNotification(
                                    ErrorTitle,
                                    "Diff is needed for running gofmt/goimports/gci. Either put <a href=\"https://ftp.gnu.org/gnu/diffutils/\">GNU diff</a> & <a href=\"https://ftp.gnu.org/pub/gnu/libiconv/\">GNU LibIconv</a> binary in PATH, or disable them",
                                    NotificationType.ERROR).apply {
                                this.setListener(NotificationListener.URL_OPENING_LISTENER)
                                this.addAction(NotificationAction.createSimple("Configure") {
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterConfigurable(project))
                                    this.expire()
                                })
                            }
                        else ->
                            notificationGroup.createNotification(
                                    ErrorTitle,
                                    "Please make sure no syntax or config error, then run 'go mod tidy' to ensure deps ok",
                                    NotificationType.WARNING).apply {
                                this.addAction(NotificationAction.createSimple("Configure") {
                                    ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterConfigurable(project))
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

                // as run failed, skip annotate and cache
                throw Exception("run failed")
            }
        }
    }
}
