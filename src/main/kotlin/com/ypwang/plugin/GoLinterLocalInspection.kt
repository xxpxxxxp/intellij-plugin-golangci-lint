package com.ypwang.plugin

import com.goide.project.GoProjectLibrariesService
import com.goide.psi.GoFile
import com.google.gson.Gson
import com.intellij.codeInspection.*
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.ypwang.plugin.form.GoLinterSettings
import com.ypwang.plugin.model.*
import com.ypwang.plugin.util.GoLinterNotificationGroup

import com.ypwang.plugin.util.Log
import com.ypwang.plugin.util.ProcessWrapper
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.locks.*
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.let as let1

class GoLinterLocalInspection : LocalInspectionTool() {
    data class InspectionResult(val timeStamp: Long = Long.MIN_VALUE, val issues: List<LintIssue>? = null)

    companion object {
        var lintResult = mutableMapOf<String, InspectionResult>()
        val instanceMutex = ReentrantLock()
        val resultMutex = ReentrantReadWriteLock()

        fun isSaved(file: PsiFile): Boolean {
            val virtualFile = file.virtualFile
            return FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.let1 {
                val fileEditorManager = FileEditorManager.getInstance(file.project)
                !fileEditorManager.isFileOpen(virtualFile) || fileEditorManager.getEditors(virtualFile).all { !it.isModified }
            } ?: false
        }
    }

    // we should allow only 1 golangci-lint instance running at the same time, or we'll drain out CPU
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        fun parseResult(issues: List<LintIssue>?, matchName: String): Array<ProblemDescriptor>? {
            if (issues == null) return null
            val rst = mutableListOf<ProblemDescriptor>()

            val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
            for (issue in issues) {
                if (issue.Pos.Filename != matchName || issue.Pos.Line > document.lineCount) continue
                val lineNumber = issue.Pos.Line - 1
                var lineStart = document.getLineStartOffset(lineNumber)
                val lineEnd = document.getLineEndOffset(lineNumber)
                if (issue.SourceLines.first() != document.getText(TextRange.create(lineStart, lineEnd)))
                    // Text not match, file is modified
                    break

                lineStart += issue.Pos.Column - 1
                if (lineStart < lineEnd) break

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

        if (file !is GoFile || !isSaved(file) || !File(GoLinterConfig.goLinterExe).canExecute()) return null     /* no linter executable */

        /* Try best to get the module directory
           As GoLand or Intellij's go plugin have to know the correct 'GOPATH' for inspections,
           we believe either 'file' should located in 1 of global 'GOPATH', or in IDE's 'Project GOPATH'
           If not, we'll use its absolute path directly,
           but in such case some linter may not working well, because they need overall symbols
        */
        val absolutePath = Paths.get(file.virtualFile.path)
        val goPluginSettings = GoProjectLibrariesService.getInstance(manager.project)
        // IDE project GOPATH
        val goPaths = goPluginSettings.state.urls.map { Paths.get(VirtualFileManager.extractPath(it), "src") }.toMutableList()
        if (goPluginSettings.isUseGoPathFromSystemEnvironment) {
            // Global GOPATH
            System.getenv("GOPATH")?.let1 { goPaths.addAll(it.split(':').map { path -> Paths.get(path, "src") }) }
        }

        val theGoPath = goPaths.singleOrNull{ absolutePath.startsWith(it) }

        var matchingName = absolutePath.fileName.toString()
        var checkName = absolutePath.fileName.toString()
        var workingDir = absolutePath.parent.toString()

        if (theGoPath != null) {
            // We located the file in 1 of 'GOPATH', extract its root dir
            // Go module in a 'GOPATH' should locate in 'GOPATH/src/'
            val subtract = theGoPath.relativize(absolutePath)

            if (subtract.nameCount > 1) {
                // absolute.parent == srcPath
                workingDir = theGoPath.toString()
                checkName = "${subtract.getName(0)}${File.separator}..."       // it's a folder
                matchingName = subtract.toString()
            }
        }

        Log.goLinter.info("Working Dir = $workingDir, Check Name = $checkName, Matching Name = $matchingName")

        resultMutex.read {
            if (workingDir in lintResult && lintResult[workingDir]!!.timeStamp >= absolutePath.toFile().lastModified()) {
                // File not modified since last run, we could reuse previous result
                return parseResult(lintResult[workingDir]!!.issues, matchingName)
            }
        }

        if (instanceMutex.tryLock()) {
            // run inspection now
            try {
                // build parameters
                val parameters = mutableListOf(GoLinterConfig.goLinterExe, "run", "--out-format", "json")
                if (GoLinterConfig.useCustomOptions) {
                    parameters.addAll(GoLinterConfig.customOptions)
                }

                // don't use to much CPU
                if (!parameters.contains("--concurrency")) {
                    parameters.add("--concurrency")
                    parameters.add(maxOf(1, Runtime.getRuntime().availableProcessors() / 4).toString())
                }

                // user customized linters
                if (!GoLinterConfig.useConfigFile && GoLinterConfig.enabledLinters != null) {
                    parameters.add("--disable-all")
                    parameters.add("-E")
                    parameters.add(GoLinterConfig.enabledLinters!!.joinToString(",") { it.split(' ').first() })
                }
                parameters.add(checkName)

                val scanResultRaw = ProcessWrapper.runWithArguments(parameters, workingDir)
                if (scanResultRaw.returnCode == 1) {     // default exit code is 1
                    resultMutex.write {
                        val rst = InspectionResult(System.currentTimeMillis(), Gson().fromJson(scanResultRaw.stdout, LintReport::class.java).Issues)
                        lintResult[workingDir] = rst
                        return parseResult(rst.issues, matchingName)
                    }
                }
                else {
                    // linter run error
                    Log.goLinter.error("Run error: ${scanResultRaw.stderr}")

                    val notification = GoLinterNotificationGroup.instance
                            .createNotification("Go linter parameters error", "golangci-lint parameters is wrongly configured", NotificationType.ERROR, null as NotificationListener?)

                    notification.addAction(NotificationAction.createSimple("Configure") {
                        ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoLinterSettings(manager.project))
                        notification.expire()
                    })

                    notification.notify(manager.project)
                }
            } finally {
                instanceMutex.unlock()
            }
        }
        // or skip current run
        return null
    }
}