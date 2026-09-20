package com.dhrashta.x.sensing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager

data class A11yFinding(
    val packageName: String,
    val isAccessibilityTool: Boolean,
    val canRetrieveWindowContent: Boolean,
    val canPerformGestures: Boolean,
    val canRequestFilterKeyEvents: Boolean,
    val listensToAllPackages: Boolean,
)

class A11yInspector(context: Context) {
    private val manager = context.getSystemService(AccessibilityManager::class.java)

    fun scan(): List<A11yFinding> = manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .mapNotNull { service ->
            val pkg = service.resolveInfo?.serviceInfo?.packageName ?: return@mapNotNull null
            A11yFinding(
                packageName = pkg,
                isAccessibilityTool = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && service.isAccessibilityTool,
                canRetrieveWindowContent = service.capabilities and
                    AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0,
                canPerformGestures = service.capabilities and
                    AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0,
                canRequestFilterKeyEvents = service.capabilities and
                    AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0,
                listensToAllPackages = service.packageNames.isNullOrEmpty(),
            )
        }
}
