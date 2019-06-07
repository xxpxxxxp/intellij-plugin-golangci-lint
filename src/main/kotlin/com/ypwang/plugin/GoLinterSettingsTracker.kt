package com.ypwang.plugin

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.ypwang.plugin.form.GoLinterSettings
import com.ypwang.plugin.util.GoLinterNotificationGroup
import java.io.File

class GoLinterSettingsTracker(val project: Project): ProjectComponent {
    override fun projectOpened() {
        // check if golangci-lint is set
        if (!File(GoLinterConfig.goLinterExe).canExecute()) {
            val notification = GoLinterNotificationGroup.instance
                .createNotification("Configure golangci-lint", "golangci-lint executable is needed for linter inspection work", NotificationType.INFORMATION, null as NotificationListener?)

            notification.addAction(NotificationAction.createSimple("Configure") {
                ShowSettingsUtil.getInstance().editConfigurable(project, GoLinterSettings(project))
                notification.expire()
            })

            notification.notify(project)
        }
    }
}