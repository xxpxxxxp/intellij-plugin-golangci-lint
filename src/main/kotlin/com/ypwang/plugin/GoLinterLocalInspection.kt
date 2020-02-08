package com.ypwang.plugin

import com.goide.project.GoProjectLibrariesService
import com.goide.psi.GoFile
import com.google.gson.Gson
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.ypwang.plugin.form.GoLinterSettings
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.LintReport
import com.ypwang.plugin.model.RunProcessResult
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

class GoLinterLocalInspection : LocalInspectionTool() {
    private class GoLinterWorkLoad(val runningPath: String, val processParameters: List<String>, val env: Map<String, String>) {
        val mutex = ReentrantLock()
        val condition: Condition = mutex.newCondition()
        var result: RunProcessResult? = null
    }

    companion object {
        private var showError = true
        private val systemGoPath = System.getenv("GOPATH")      // immutable in current idea process

        // consumer queue
        private val workLoads = sortedMapOf<String, GoLinterWorkLoad>()
        // a singleton thread to execute go-linter, to avoid multiple instance drain out CPU
        private val executor = Executors.newSingleThreadExecutor()

        private fun run() {
            var head: GoLinterWorkLoad? = null
            synchronized(workLoads) {
                // pop LIFO
                if (workLoads.isNotEmpty()) {
                    val key = workLoads.firstKey()
                    head = workLoads[key]
                    workLoads.remove(key)
                }
            }

            if (head != null) {
                // executing
                head!!.result = fetchProcessOutput(ProcessBuilder(head!!.processParameters).apply {
                    val curEnv = this.environment()
                    head!!.env.forEach{ kv -> curEnv[kv.key] = kv.value }
                    this.directory(File(head!!.runningPath))
                }.start())
                head!!.mutex.lock()
                head!!.condition.signal()
                head!!.mutex.unlock()
            }
        }

        // running cache
        private val cache = mutableMapOf<String, Pair<Long, List<LintIssue>>>()     // cache targetPath <> (timestamp, issues)

        fun findCustomConfig(project: Project): String {
            val basePath: String? = project.basePath
            if (basePath != null) {
                var cur: Path? = Paths.get(basePath)
                while (cur != null && cur.toFile().isDirectory) {
                    for (s in arrayOf(".golangci.yml", ".golangci.toml", ".golangci.json")) {
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

        private var useCustomConfig: Boolean = false
        private var timestamp = Long.MIN_VALUE
        private fun useCustomConfig(project: Project): Boolean {
            // cache the result max 10s
            if (timestamp + 10000 < System.currentTimeMillis()) {
                useCustomConfig = findCustomConfig(project).isNotEmpty()
                timestamp = System.currentTimeMillis()
            }

            return useCustomConfig
        }
//        fun isSaved(file: PsiFile): Boolean {
//            val virtualFile = file.virtualFile
//            return FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.let {
//                val fileEditorManager = FileEditorManager.getInstance(file.project)
//                !fileEditorManager.isFileOpen(virtualFile) || fileEditorManager.getEditors(virtualFile).all { !it.isModified }
//            } ?: false
//        }
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
                if (issue.SourceLines.first() != document.getText(TextRange.create(lineStart, lineEnd)))
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
                if (module in cache && lastModifyTimestamp < cache[module]!!.first) {
                    issues = cache[module]!!.second
                }
            }

            if (issues != null) {
                return matchAndShow(issues!!, matchName)
            }
        }

        // cache not found or outdated
        // try best to get GOPATH, as GoLand or Intellij's go plugin have to know the correct 'GOPATH' for inspections,
        // ful GOPATH should be: Global GOPATH + IDE project GOPATH
        // IDE's take precedence
        val goPluginSettings = GoProjectLibrariesService.getInstance(manager.project)
        val goPaths =
                (if (goPluginSettings.isUseGoPathFromSystemEnvironment && systemGoPath != null) systemGoPath + File.pathSeparator else "") +
                        goPluginSettings.state.urls.map{ Paths.get(VirtualFileManager.extractPath(it)) }.joinToString(File.pathSeparator)

        // build parameters
        val parameters = mutableListOf(GoLinterConfig.goLinterExe, "run", "--out-format", "json")
        val provides = mutableSetOf<String>()

        if (GoLinterConfig.customOptions != null) {
            parameters.add(GoLinterConfig.customOptions!!)
            provides.addAll(GoLinterConfig.customOptions!!.split(" "));
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

        // user customized linters
        if (useCustomConfig(manager.project) && GoLinterConfig.enabledLinters != null) {
            parameters.add("--disable-all")
            parameters.add("-E")
            parameters.add(GoLinterConfig.enabledLinters!!.joinToString(",") { it.split(' ').first() })
        }
        parameters.add(".")

        val now = System.currentTimeMillis()
        val workLoad = GoLinterWorkLoad(module, parameters, mapOf("GOPATH" to goPaths))
        workLoad.mutex.lock()

        synchronized(workLoads) {
            val that = workLoads[module]
            if (that != null) {
                // newer is better, preempt old one
                workLoads.remove(module)
                that.mutex.lock()
                that.condition.signal()
                that.mutex.unlock()
            }

            workLoads[module] = workLoad
        }

        executor.execute { run() }
        // wait for worker done the job or been preempted
        workLoad.condition.await()

        if (workLoad.result != null) {
            val processResult = workLoad.result!!
            if (processResult.returnCode == 1) {    // default exit code is 1
                val parsed = Gson().fromJson(processResult.stdout, LintReport::class.java).Issues
                synchronized(cache) {
                    cache[module] = now to parsed
                }
                return matchAndShow(parsed, matchName)
            } else {
                // linter run error
                logger.error("Run error: ${processResult.stderr}")

                if (showError) {
                    val notification = notificationGroup
                            .createNotification("Go linter parameters error", "golangci-lint parameters is wrongly configured", NotificationType.ERROR, null as NotificationListener?)

                    notification.addAction(NotificationAction.createSimple("Configure") {
                        ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoLinterSettings(manager.project))
                        notification.expire()
                    })

                    notification.addAction(NotificationAction.createSimple("Do not show again") {
                        showError = false
                        notification.expire()
                    })

                    notification.notify(manager.project)
                }
            }
        }

        workLoad.mutex.unlock()

        // or skip current run
        return null
    }
}