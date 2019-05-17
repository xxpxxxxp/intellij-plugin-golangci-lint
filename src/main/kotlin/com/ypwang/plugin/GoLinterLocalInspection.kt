package com.ypwang.plugin

import com.goide.project.GoProjectLibrariesService
import com.goide.psi.GoFile
import com.google.gson.Gson
import com.intellij.codeInspection.*
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
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

import com.ypwang.plugin.util.Log
import com.ypwang.plugin.util.ProcessWrapper
import java.io.File
import java.nio.file.Paths
import javax.management.Notification

class GoLinterLocalInspection : LocalInspectionTool() {
    companion object {
        var lintResult = mutableMapOf<String, Pair<Long, List<LintIssue>>>()

        fun isSaved(file: PsiFile): Boolean {
            val virtualFile = file.virtualFile
            val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile)

            return if (document != null) {
                val fileEditorManager = FileEditorManager.getInstance(file.project)
                !fileEditorManager.isFileOpen(virtualFile) || fileEditorManager.getEditors(virtualFile).all { !it.isModified }
            } else false
        }
    }

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        Log.golinter.info("Into my plugin")
        if (!File(GoLinterConfig.goLinterExe).canExecute()) return null     // no linter executable

        if (file !is GoFile && isSaved(file))
            return null

        /* Try best to get the module directory
           As GoLand or Intellij's go plugin have to know the correct 'GOPATH' for inspections,
           we believe either 'file' should located in 1 of global 'GOPATH', or in IDE's 'Project GOPATH'
           If not, we'll use its absolute path directly,
           but in such case some linter may not working well, because they need overall symbols
        */
        val absolutePath = file.virtualFile.path

        val goPluginSettings = GoProjectLibrariesService.getInstance(manager.project)
        val goPaths = goPluginSettings.state.urls.map { VirtualFileManager.extractPath(it) }.toMutableList()
        if (goPluginSettings.isUseGoPathFromSystemEnvironment) {
            System.getenv("GOPATH")?.let { goPaths.addAll(it.split(':')) }
        }

        val theGoPath = goPaths.singleOrNull{ absolutePath.startsWith(it) }
        var absolute = Paths.get(absolutePath)

        var matchingName = absolute.fileName.toString()
        var checkName = absolute.fileName.toString()
        var workingDir = absolute.parent.toString()

        if (theGoPath != null) {
            // If we locate the file in 1 of 'GOPATH', extract its root dir
            // Go module in a 'GOPATH' should locate in 'GOPATH/src/'
            val srcPath = Paths.get("$theGoPath/src")
            while (true) {
                val parent = absolute.parent
                if (parent == null || parent == srcPath) break
                absolute = parent
            }

            if (absolute.parent != null) {
                // absolute.parent == srcPath
                workingDir = srcPath.toString()
                checkName = "${absolute.fileName}/..."       // it's a folder
                matchingName = absolutePath.substring(workingDir.length + 1)
            }
        }

        Log.golinter.info("Working Dir: $workingDir")
        Log.golinter.info("Check Name: $checkName")
        Log.golinter.info("Matching Name: $matchingName")

        // newly opened file without modified could benefit from previous run
        if (workingDir !in lintResult || lintResult[workingDir]!!.first < File(absolutePath).lastModified()) {
            // build parameters
            val parametes = mutableListOf(GoLinterConfig.goLinterExe, "--out-format", "json")

            if (GoLinterConfig.useCustomOptions) {
                parametes.addAll(GoLinterConfig.customOptions)
            }

            if (!GoLinterConfig.useConfigFile && GoLinterConfig.enabledLinters != null) {
                parametes.add("--disable-all")
                parametes.add("-E")
                parametes.add(GoLinterConfig.enabledLinters!!.joinToString(","))
            }

            val scanResultRaw = ProcessWrapper.runWithArguments(parametes, workingDir)
            if (scanResultRaw.returnCode == 0)
                lintResult[workingDir] = System.currentTimeMillis() to Gson().fromJson(scanResultRaw.stdout, LintReport::class.java).Issues
            else {
                // linter run error, clean cache
                lintResult.remove(workingDir)

                val notification = NotificationGroup.balloonGroup("Go linter notifications")
                    .createNotification("Go linter parameters error", "golangci-lint parameters is wrongly configured", NotificationType.ERROR, null as NotificationListener?)

                notification.addAction(NotificationAction.createSimple("Configure") {
                    ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoLinterSettings(manager.project))
                    notification.expire()
                })

                notification.notify(manager.project)
            }
        }

        //lintResult[workingDir] = System.currentTimeMillis() to Gson().fromJson(File("/Users/ypwang/sources/dev/goland-linter/src/main/resources/test.json").readText(), LintReport::class.java).Issues

        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        return lintResult[workingDir]?.let { kv ->
            kv.second.filter { it.Pos.Filename == matchingName && it.Pos.Line - 1 < document.lineCount }
                .map { issue ->
                    val lineNumber = issue.Pos.Line - 1
                    val lineStart = document.getLineStartOffset(lineNumber)
                    val lineEnd = document.getLineEndOffset(lineNumber)

                    manager.createProblemDescriptor(
                        file,
                        TextRange.create(
                            maxOf(issue.Pos.Offset, lineStart),
                            minOf(issue.Pos.Offset + issue.SourceLines.first().length, lineEnd)
                        ),
                        "${issue.Text} (${issue.FromLinter})",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        isOnTheFly
                    )
                }.toTypedArray()
        }
    }
}