package de.fraunhofer.iem

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notification {
    fun notifyInfo(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SecurityMarkerNotifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}