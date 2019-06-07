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

class GoLinterLocalInspection : LocalInspectionTool() {
    class InspectionResult {
        var timeStamp: Long = Long.MIN_VALUE
        var mutex: Lock = ReentrantLock()
        var issues: List<LintIssue>? = null
    }

    companion object {
        var lintResult = mutableMapOf<String, InspectionResult>()
        val mapMutex = ReentrantReadWriteLock()

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
        if (!File(GoLinterConfig.goLinterExe).canExecute()) return null     // no linter executable

        if (file !is GoFile && isSaved(file))
            return null

        /* Try best to get the module directory
           As GoLand or Intellij's go plugin have to know the correct 'GOPATH' for inspections,
           we believe either 'file' should located in 1 of global 'GOPATH', or in IDE's 'Project GOPATH'
           If not, we'll use its absolute path directly,
           but in such case some linter may not working well, because they need overall symbols
        */
        val absolutePath = Paths.get(file.virtualFile.path)

        val goPluginSettings = GoProjectLibrariesService.getInstance(manager.project)
        val goPaths = goPluginSettings.state.urls.map { Paths.get(VirtualFileManager.extractPath(it), "src") }.toMutableList()
        if (goPluginSettings.isUseGoPathFromSystemEnvironment) {
            System.getenv("GOPATH")?.let { goPaths.addAll(it.split(':').map { path -> Paths.get(path, "src") }) }
        }

        val theGoPath = goPaths.singleOrNull{ absolutePath.startsWith(it) }

        var matchingName = absolutePath.fileName.toString()
        var checkName = absolutePath.fileName.toString()
        var workingDir = absolutePath.parent.toString()

        if (theGoPath != null) {
            // If we locate the file in 1 of 'GOPATH', extract its root dir
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

        // intellij will instant as many inspection clazz as opened tab
        // for those tab share same folder, we could just run once lint
        // for those tab don't share folder, we should allow them to run in parallel
        mapMutex.read {
            if (workingDir !in lintResult) {
                mapMutex.write {
                    lintResult[workingDir] = InspectionResult()
                }
            }

            val rstRefer = lintResult[workingDir]!!
            // newly opened file without modified could benefit from previous run
            if (rstRefer.timeStamp < absolutePath.toFile().lastModified()) {
                // run inspection now
                try {
                    if (rstRefer.mutex.tryLock()) {
                        Log.goLinter.info("Last run time ${rstRefer.timeStamp}, file change stamp ${absolutePath.toFile().lastModified()}")
                        // locked, run now
                        // build parameters
                        val parameters = mutableListOf(GoLinterConfig.goLinterExe, "run", "--out-format", "json")
                        if (GoLinterConfig.useCustomOptions) {
                            parameters.addAll(GoLinterConfig.customOptions)
                        }

                        if (!parameters.contains("--concurrency")) {
                            parameters.add("--concurrency")
                            parameters.add(maxOf(1, Runtime.getRuntime().availableProcessors() / 4).toString())
                        }

                        if (!GoLinterConfig.useConfigFile && GoLinterConfig.enabledLinters != null) {
                            parameters.add("--disable-all")
                            parameters.add("-E")
                            parameters.add(GoLinterConfig.enabledLinters!!.joinToString(",") { it.split(' ').first() })
                        }
                        parameters.add(checkName)

                        val scanResultRaw = ProcessWrapper.runWithArguments(parameters, workingDir)
                        if (scanResultRaw.returnCode == 1) {     // default exit code is 1
                            rstRefer.timeStamp = System.currentTimeMillis()
                            rstRefer.issues = Gson().fromJson(scanResultRaw.stdout, LintReport::class.java).Issues
                        }
                        else {
                            // linter run error, clean cache
                            Log.goLinter.error("Run error: ${scanResultRaw.stderr}")
                            rstRefer.timeStamp = Long.MIN_VALUE
                            rstRefer.issues = null

                            val notification = GoLinterNotificationGroup.instance
                                .createNotification("Go linter parameters error", "golangci-lint parameters is wrongly configured", NotificationType.ERROR, null as NotificationListener?)

                            notification.addAction(NotificationAction.createSimple("Configure") {
                                ShowSettingsUtil.getInstance().editConfigurable(manager.project, GoLinterSettings(manager.project))
                                notification.expire()
                            })

                            notification.notify(manager.project)
                        }
                    } else {
                        // blocking now
                        Log.goLinter.info("Collide with another running instance, wait for other's result")
                        rstRefer.mutex.lock()
                    }
                } finally {
                    rstRefer.mutex.unlock()
                }
            }

            val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
            return rstRefer.issues?.let { kv ->
                kv.asSequence().filter { it.Pos.Filename == matchingName && it.Pos.Line - 1 < document.lineCount }
                    .map { issue ->
                        val lineNumber = issue.Pos.Line - 1
                        val lineStart = document.getLineStartOffset(lineNumber)
                        val lineEnd = document.getLineEndOffset(lineNumber)

                        Triple(lineStart + issue.Pos.Column - 1, lineEnd, "${issue.Text} (${issue.FromLinter})")
                    }.filter {
                        // this may happen on hot edit, PsiFile is not updated as soon as the file saved
                        it.first <= it.second
                    }.map { (start, end, text) ->
                        manager.createProblemDescriptor(
                            file,
                            TextRange.create(start, end),
                            text,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            isOnTheFly
                        )
                    }.toList().toTypedArray()
            }
        }
    }
}