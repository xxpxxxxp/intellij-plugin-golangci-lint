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
import com.ypwang.plugin.form.GoLinterConfigurable
import com.ypwang.plugin.model.GithubRelease
import com.ypwang.plugin.model.GolangciLintVersion
import com.ypwang.plugin.platform.platformFactory
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClientBuilder

class GoLinterSettingsTracker : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        try {
            val settings = GoLinterSettings.getInstance(project)
            if (!settings.checkGoLinterExe)
                return

            val platform = platformFactory(project)
            if (!platform.canExecute(settings.goLinterExe)) {
                noExecutableNotification(project)
                return
            }

            val result = platform.runProcess(
                listOf(settings.goLinterExe, "version", "--format", "json"),
                null,
                mapOf("PATH" to getSystemPath(project))
            )
            when (result.returnCode) {
                0 ->
                    // golangci-lint has switched the output between stdout and stderr
                    // for backward capability, try both
                    for (str in listOf(result.stderr, result.stdout).filter { it.isNotEmpty() }) {
                        try {
                            val version = Gson().fromJson(str, GolangciLintVersion::class.java).version
                            checkExecutableUpdate(version, project)
                            return
                        } catch (e: JsonSyntaxException) {
                            // skip parse fail
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
                ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterConfigurable(project))
                this.expire()
            })
            this.addAction(NotificationAction.createSimple("Don't show for this project") {
                GoLinterSettings.getInstance(project).checkGoLinterExe = false
                this.expire()
            })
        }.notify(project)
    }

    private fun checkExecutableUpdate(curVersion: String, project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "check golangci-lint updates") {
            override fun run(pi: ProgressIndicator) {
                pi.isIndeterminate = true

                try {
                    val timeout = 3000  // 3000ms
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
        val platform = platformFactory(project)
        val platformBinName = platform.getPlatformSpecificBinName(latestMeta)
        val url = latestMeta.assets.single { it.name == platformBinName }.browserDownloadUrl

        notificationGroup.createNotification(
            title,
            "Download <a href=\"$url\">${latestMeta.name}</a> in browser",
            NotificationType.INFORMATION
        ).apply {
            this.setListener(NotificationListener.URL_OPENING_LISTENER)

            val dest = GoLinterSettings.getInstance(project).goLinterExe
            // if unable to write the golangci-lint, don't provide update action
            if (!platform.canWrite(dest)) {
                return@apply
            }

            // update action
            this.addAction(NotificationAction.createSimple("Update in background") {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(project, "update golangci-lint") {
                        override fun run(indicator: ProgressIndicator) {
                            try {
                                platform.fetchLatestGoLinter(
                                    platform.parentFolder(dest),
                                    { s -> indicator.text = s },
                                    { f -> indicator.fraction = f },
                                    { indicator.isCanceled }
                                )

                                // update succeed!
                                notificationGroup.createNotification(
                                    "golangci-lint updated to ${latestMeta.name}",
                                    latestMeta.body,
                                    NotificationType.INFORMATION
                                ).apply {
                                    this.addAction(NotificationAction.createSimple("Check config") {
                                        ShowSettingsUtil.getInstance()
                                            .editConfigurable(project, GoLinterConfigurable(project))
                                        this.expire()
                                    })
                                }.notify(project)
                            } catch (e: ProcessCanceledException) {
                                // proactively cancelled by user, ignore
                            } catch (e: Exception) {
                                // update failed!
                                notificationGroup.createNotification(
                                    "update golangci-lint failed",
                                    "Error: ${e.message}",
                                    NotificationType.ERROR
                                ).notify(project)
                            }
                        }
                    }
                )
                this.expire()
            })
            this.addAction(NotificationAction.createSimple("Don't show for this project") {
                GoLinterSettings.getInstance(project).checkGoLinterExe = false
                this.expire()
            })
        }.notify(project)
    }
}