package com.ypwang.plugin.util

import com.intellij.notification.NotificationGroup

object GoLinterNotificationGroup {
    val instance = NotificationGroup.balloonGroup("Go linter notifications")
}