package com.ypwang.plugin

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.ypwang.plugin.form.GoLinterSettings
import com.ypwang.plugin.model.GithubRelease
import com.ypwang.plugin.model.GolangciLintVersion
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClientBuilder
import java.io.File

class GoLinterSettingsTracker : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        try {
            if (GoLinterConfig.checkGoLinterExe) {
                if (File(GoLinterConfig.goLinterExe).canExecute()) {
                    val result = GolangCiOutputParser.runProcess(
                        listOf(GoLinterConfig.goLinterExe, "version", "--format", "json"),
                        null,
                        mapOf("PATH" to getSystemPath(project))
                    )
                    when (result.returnCode) {
                        0 ->
                            // for backward capability, try both stdout and stderr
                            for (str in listOf(result.stderr, result.stdout).filter { it.isNotEmpty() }) {
                                try {
                                    checkExecutableUpdate(Gson().fromJson(str, GolangciLintVersion::class.java).version, project)
                                    return
                                } catch (e: JsonSyntaxException) {
                                    // ignore
                                }
                            }
                        2 -> {
                            // panic!
                            if (isGo18(project))
                                notificationGroup.createNotification(
                                    "Incompatible golangci-lint with Go1.18",
                                    "Please update golangci-lint after v1.45.0",
                                    NotificationType.INFORMATION
                                ).notify(project)
                            return
                        }
                    }
                }

                noExecutableNotification(project)
            }
        } catch (ignore: Throwable) {
            // ignore
        }
    }

    private fun noExecutableNotification(project: Project) {
        notificationGroup.createNotification(
            "Configure golangci-lint",
            "golangci-lint executable is needed for linter inspection work. <a href=\"https://github.com/xxpxxxxp/intellij-plugin-golangci-lint\">Checkout guide</a>",
            NotificationType.INFORMATION
        ).apply {
            this.setListener(NotificationListener.URL_OPENING_LISTENER)
            this.addAction(NotificationAction.createSimple("Configure") {
                ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
                this.expire()
            })
            this.addAction(NotificationAction.createSimple("Don't show for this project") {
                GoLinterConfig.checkGoLinterExe = false
                this.expire()
            })
        }.notify(project)
    }

    private fun checkExecutableUpdate(curVersion: String, project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "check golangci-lint updates") {
            override fun run(pi: ProgressIndicator) {
                pi.isIndeterminate = true

                try {
                    val timeout = 3000
                    val latestMeta = HttpClientBuilder.create()
                        .disableContentCompression()
                        .setDefaultRequestConfig(
                            RequestConfig.custom()
                                .setConnectTimeout(timeout)
                                .setConnectionRequestTimeout(timeout)
                                .setSocketTimeout(timeout).build()
                        )
                        .build()
                        .use { getLatestReleaseMeta(it) }
                    val latestVersion = latestMeta.name.substring(1)
                    if (!curVersion.matches(Regex("""\d+\.\d+\.\d+""")))
                        updateNotification(project, "golangci-lint is custom built: $curVersion", latestMeta)
                    else if (compareVersion(latestVersion, curVersion) > 0)
                        updateNotification(project, "golangci-lint update available", latestMeta)
                } catch (e: Exception) {
                    // ignore
                }
            }
        })
    }

    private fun updateNotification(project: Project, title: String, latestMeta: GithubRelease) {
        val platformBinName = getPlatformSpecificBinName(latestMeta)
        val url = latestMeta.assets.single { it.name == platformBinName }.browserDownloadUrl

        notificationGroup.createNotification(
            title,
            "Download <a href=\"$url\">${latestMeta.name}</a> in browser",
            NotificationType.INFORMATION
        ).apply {
            this.setListener(NotificationListener.URL_OPENING_LISTENER)

            val file = File(GoLinterConfig.goLinterExe)
            if (file.canWrite()) {
                // provide update action
                this.addAction(NotificationAction.createSimple("Update in background") {
                    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "update golangci-lint") {
                        override fun run(indicator: ProgressIndicator) {
                            try {
                                fetchLatestGoLinter(
                                    file.parent,
                                    { s -> indicator.text = s },
                                    { f -> indicator.fraction = f },
                                    { indicator.isCanceled })
                                updateSucceedNotification(project, latestMeta)
                            } catch (e: ProcessCanceledException) {
                                // ignore
                            } catch (e: Exception) {
                                notificationGroup.createNotification(
                                    "update golangci-lint failed",
                                    "Error: ${e.message}",
                                    NotificationType.ERROR
                                ).notify(project)
                            }
                        }
                    })
                    this.expire()
                })
            }
        }.notify(project)
    }

    private fun updateSucceedNotification(project: Project, latestMeta: GithubRelease) {
        notificationGroup.createNotification(
            "golangci-lint updated to ${latestMeta.name}",
            latestMeta.body,
            NotificationType.INFORMATION
        ).apply {
            this.addAction(NotificationAction.createSimple("Check config") {
                ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
                this.expire()
            })
        }.notify(project)
    }
}