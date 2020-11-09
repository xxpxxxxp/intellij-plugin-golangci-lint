package com.ypwang.plugin

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
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClientBuilder
import java.nio.file.Paths

class GoLinterSettingsTracker: StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        try {
            if (GoLinterConfig.checkGoLinterExe) {
                // check if golangci-lint is set
                getGolangCiVersion(GoLinterConfig.goLinterExe).ifPresentOrElse(
                        { checkExecutableUpdate(it, project) },
                        { noExecutableNotification(project) }
                )
            }
        } catch (ignore: Throwable) {
            // ignore
        }
    }

    private fun noExecutableNotification(project: Project) {
        notificationGroup.createNotification(
                "Configure golangci-lint",
                "golangci-lint executable is needed for linter inspection work. <a href=\"https://github.com/xxpxxxxp/intellij-plugin-golangci-lint\">Checkout guide</a>",
                NotificationType.INFORMATION,
                NotificationListener.URL_OPENING_LISTENER
        ).apply {
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
                            .setDefaultRequestConfig(RequestConfig.custom()
                                    .setConnectTimeout(timeout)
                                    .setConnectionRequestTimeout(timeout)
                                    .setSocketTimeout(timeout).build())
                            .build()
                            .use { getLatestReleaseMeta(it) }
                    val latestVersion = latestMeta.name.substring(1)

                    if (curVersion.matches(Regex("""\d+\.\d+\.\d+"""))) {
                        val versionDiff = latestVersion.split('.')
                                .zip(curVersion.split('.'))
                                .firstOrNull { it.first != it.second }
                                ?: return
                        if (versionDiff.first.toInt() > versionDiff.second.toInt())
                            updateNotification(project, "golangci-lint update available", latestMeta)
                    } else {
                        updateNotification(project, "golangci-lint is custom built: $curVersion", latestMeta)
                    }
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
                NotificationType.INFORMATION,
                NotificationListener.URL_OPENING_LISTENER).apply {
            if (Paths.get(GoLinterConfig.goLinterExe).startsWith(executionDir)) {
                // downloads / control by us, provide update action
                this.addAction(NotificationAction.createSimple("Update in background") {
                    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "update golangci-lint") {
                        override fun run(indicator: ProgressIndicator) {
                            try {
                                fetchLatestGoLinter({ s -> indicator.text = s }, { f -> indicator.fraction = f }, { indicator.isCanceled })
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