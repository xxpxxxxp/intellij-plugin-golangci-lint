package com.ypwang.plugin

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.ypwang.plugin.form.GoLinterSettings
import java.io.File

class GoLinterSettingsTracker: StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        // check if golangci-lint is set
        if (!File(GoLinterConfig.goLinterExe).canExecute()) {
            val notification = notificationGroup
                    .createNotification("Configure golangci-lint", "golangci-lint executable is needed for linter inspection work", NotificationType.INFORMATION, null as NotificationListener?)

            notification.addAction(NotificationAction.createSimple("Configure") {
                ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
                notification.expire()
            })

            notification.notify(project)
        }
    }
}