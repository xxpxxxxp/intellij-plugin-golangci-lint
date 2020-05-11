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
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClientBuilder
import java.nio.file.Paths

class GoLinterSettingsTracker: StartupActivity.DumbAware {
    private fun noExecutableNotification(project: Project) {
        notificationGroup.createNotification(
                "Configure golangci-lint",
                "golangci-lint executable is needed for linter inspection work",
                NotificationType.INFORMATION).apply {
            this.addAction(NotificationAction.createSimple("Configure") {
                ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
                this.expire()
            })
        }.notify(project)
    }

    private fun checkExecutableUpdate(curVersion: String, project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "check golangci-lint updates") {
            override fun run(pi: ProgressIndicator) {
                pi.isIndeterminate = true

                val timeout = 3000
                try {
                    val latest = HttpClientBuilder.create()
                            .disableContentCompression()
                            .setDefaultRequestConfig(RequestConfig.custom()
                                    .setConnectTimeout(timeout)
                                    .setConnectionRequestTimeout(timeout)
                                    .setSocketTimeout(timeout).build())
                            .build()
                            .use { getLatestReleaseMeta(it) }
                    val latestVersion = latest.name.substring(1)
                    val versionDiff = latestVersion.split('.').zip(curVersion.split('.')).firstOrNull { it.first != it.second } ?: return
                    if (versionDiff.first.toInt() > versionDiff.second.toInt()) {
                        val platformBinName = getPlatformSpecificBinName(latest)
                        val url = latest.assets.single { it.name == platformBinName }.browserDownloadUrl
                        notificationGroup.createNotification(
                                "golangci-lint update available",
                                "Download <a href=\"$url\">${latest.name}</a> in browser",
                                NotificationType.INFORMATION,
                                NotificationListener.URL_OPENING_LISTENER).apply {
                            if (Paths.get(GoLinterConfig.goLinterExe).startsWith(executionDir)) {
                                // downloads / control by us, provide update action
                                this.addAction(NotificationAction.createSimple("Update in background") {
                                    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "update golangci-lint") {
                                        override fun run(indicator: ProgressIndicator) {
                                            try {
                                                fetchLatestGoLinter({ s -> indicator.text = s }, { f -> indicator.fraction = f }, { indicator.isCanceled })
                                                notificationGroup.createNotification(
                                                        "golangci-lint updated to ${latest.name}",
                                                        latest.body,
                                                        NotificationType.INFORMATION
                                                ).notify(project)
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
                } catch (e: Exception) {
                    // ignore
                }
            }
        })
    }

    override fun runActivity(project: Project) {
        try {
            // check if golangci-lint is set
            val version = getGolangCiVersion(GoLinterConfig.goLinterExe)
            if (version.isEmpty()) noExecutableNotification(project)
            else checkExecutableUpdate(version, project)
        } catch (ignore: Throwable) {
            // ignore
        }
    }
}