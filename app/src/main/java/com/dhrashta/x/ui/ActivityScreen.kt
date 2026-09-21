package com.dhrashta.x.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhrashta.x.data.Connection
import com.dhrashta.x.data.Event
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.data.RiskScore
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.theme.Amber
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Danger
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Info
import com.dhrashta.x.ui.theme.MutedInk

enum class ActivityKind { Alert, Review, Network, Device }

/** One row in the activity timeline. [pkg] is null for device-wide entries. */
data class ActivityItem(
    val key: String,
    val timestamp: Long,
    val kind: ActivityKind,
    val title: String,
    val detail: String,
    val pkg: String?,
)

/**
 * Builds the timeline from Room: engine reviews, behavioural events and blocked connections.
 * Rule-signal rows are skipped here (they are shown as evidence on the app screen), and blocked
 * connections are grouped per app per minute so the list stays readable.
 */
fun buildActivity(
    scores: List<RiskScore>,
    events: List<Event>,
    connections: List<Connection>,
    labels: Map<String, String>,
    packagesByUid: Map<Int, String>,
    installed: List<InstalledApp> = emptyList(),
): List<ActivityItem> {
    fun name(pkg: String) = labels[pkg] ?: pkg
    val items = mutableListOf<ActivityItem>()
    scores.forEach { score ->
        val level = levelFor(score.score)
        items += ActivityItem(
            key = "score-${score.id}",
            timestamp = score.timestamp,
            kind = if (level >= RiskLevel.High) ActivityKind.Alert else ActivityKind.Review,
            title = "${name(score.pkg)} reviewed",
            detail = "Risk score ${score.score} · ${levelText(level)}",
            pkg = score.pkg,
        )
    }
    events.forEach { event ->
        val device = event.pkg == EventLogger.DEVICE_PKG
        val (kind, title) = when (event.signalId) {
            EventLogger.CANARY_READ -> ActivityKind.Alert to "${name(event.pkg)} sent decoy data off the device"
            EventLogger.BEACON_UNKNOWN_HOST -> ActivityKind.Network to "${name(event.pkg)} contacted a server on a fixed schedule"
            EventLogger.A11Y_ENABLED -> ActivityKind.Review to "Accessibility access turned on for ${name(event.pkg)}"
            EventLogger.SIDELOAD -> ActivityKind.Review to "${name(event.pkg)} installed outside Play Store"
            EventLogger.BANK_FOREGROUND -> ActivityKind.Device to "Banking app opened"
            EventLogger.ADB_ENABLED -> ActivityKind.Device to "USB or wireless debugging turned on"
            EventLogger.WORK_PROFILE_CREATED -> ActivityKind.Device to "Work profile created"
            EventLogger.CLONED_APP_LAUNCHED -> ActivityKind.Device to "An app was added to a second profile"
            else -> return@forEach
        }
        items += ActivityItem(
            key = "event-${event.id}",
            timestamp = event.timestamp,
            kind = kind,
            title = title,
            detail = if (device) "On this phone" else event.pkg,
            pkg = event.pkg.takeUnless { device },
        )
    }
    connections.filter { it.blocked }
        .groupBy { it.uid to it.timestamp / DateUtils.MINUTE_IN_MILLIS }
        .forEach { (group, rows) ->
            val pkg = packagesByUid[group.first]
            items += ActivityItem(
                key = "blocked-${group.first}-${group.second}",
                timestamp = rows.maxOf { it.timestamp },
                kind = ActivityKind.Network,
                title = "${rows.size} connection${if (rows.size == 1) "" else "s"} blocked",
                detail = pkg?.let(::name) ?: "App UID ${group.first}",
                pkg = pkg,
            )
        }
    val since = System.currentTimeMillis() - INSTALL_HISTORY_MILLIS
    installed.filter { !it.isSystem && it.firstInstallTime >= since }.forEach { app ->
        items += ActivityItem(
            key = "install-${app.pkg}",
            timestamp = app.firstInstallTime,
            kind = if (app.sideloaded) ActivityKind.Review else ActivityKind.Device,
            title = "${app.label} installed",
            detail = sourceText(app),
            pkg = app.pkg,
        )
    }
    return items.sortedByDescending { it.timestamp }
}

private const val INSTALL_HISTORY_MILLIS = 30 * DateUtils.DAY_IN_MILLIS

fun levelText(level: RiskLevel) = when (level) {
    RiskLevel.Safe -> "No issues found"
    RiskLevel.Review -> "Review recommended"
    RiskLevel.High -> "High risk"
    RiskLevel.Critical -> "Critical risk"
}

/** Relative time such as "5 minutes ago". */
fun relativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    if (now - timestamp < DateUtils.MINUTE_IN_MILLIS) return "Just now"
    return DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

private enum class ActivityFilter(val label: String) { All("All"), Alerts("Alerts"), Network("Network"), Device("Device") }

@Composable
fun ActivityScreen(
    items: List<ActivityItem>,
    labels: Map<String, String>,
    onOpenApp: (String) -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    BackHandler(onBack = onHome)
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    val visible = items.filter {
        when (filter) {
            ActivityFilter.All -> true
            ActivityFilter.Alerts -> it.kind == ActivityKind.Alert || it.kind == ActivityKind.Review
            ActivityFilter.Network -> it.kind == ActivityKind.Network
            ActivityFilter.Device -> it.kind == ActivityKind.Device
        }
    }
    Column(Modifier.fillMaxSize().background(Canvas)) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = ScreenPadding)) {
            item {
                Spacer(Modifier.height(26.dp))
                Text("Activity", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Everything DHRASHTAX noticed, newest first.",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ForestSoft,
                                selectedLabelColor = Forest,
                            ),
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    QuietCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Reviews, blocked connections and device changes will appear here as they happen.",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            items(visible, key = { it.key }) { item ->
                ActivityRow(item, labels, onOpenApp)
                Spacer(Modifier.height(10.dp))
            }
            item { Spacer(Modifier.height(14.dp)) }
        }
        BottomNavigation(active = BottomDestination.Activity, onHome = onHome, onActivity = {}, onSettings = onSettings)
    }
}

@Composable
fun ActivityRow(item: ActivityItem, labels: Map<String, String>, onOpenApp: (String) -> Unit) {
    val pkg = item.pkg
    val clickable = pkg != null && pkg in labels
    QuietCard(Modifier.fillMaxWidth().then(if (clickable) Modifier.clickable { onOpenApp(pkg!!) } else Modifier)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pkg != null && pkg in labels) {
                AppIcon(pkg, labels.getValue(pkg), Modifier.size(38.dp))
            } else {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    LineIcon(LineIconType.Activity, Modifier.size(22.dp), MutedInk)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${relativeTime(item.timestamp)} · ${item.detail}",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.padding(start = 8.dp).size(8.dp).background(kindColor(item.kind), CircleShape))
        }
    }
}

private fun kindColor(kind: ActivityKind): Color = when (kind) {
    ActivityKind.Alert -> Danger
    ActivityKind.Review -> Amber
    ActivityKind.Network -> Info
    ActivityKind.Device -> Forest
}
