package com.dhrashta.x.ui

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhrashta.x.R
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
 * Builds the timeline from Room: engine reviews, behavioural events, blocked connections and
 * recent installs. Rule-signal rows are skipped here (they are shown as evidence on the app
 * screen), and blocked connections are grouped per app per minute so the list stays readable.
 */
fun buildActivity(
    res: Resources,
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
            title = res.getString(R.string.act_reviewed, name(score.pkg)),
            detail = res.getString(R.string.act_score_detail, score.score, levelText(res, level)),
            pkg = score.pkg,
        )
    }
    events.forEach { event ->
        val device = event.pkg == EventLogger.DEVICE_PKG
        val (kind, title) = when (event.signalId) {
            EventLogger.CANARY_READ -> ActivityKind.Alert to res.getString(R.string.act_canary, name(event.pkg))
            EventLogger.BEACON_UNKNOWN_HOST -> ActivityKind.Network to res.getString(R.string.act_beacon, name(event.pkg))
            EventLogger.A11Y_ENABLED -> ActivityKind.Review to res.getString(R.string.act_a11y, name(event.pkg))
            EventLogger.SIDELOAD -> ActivityKind.Review to res.getString(R.string.act_sideload, name(event.pkg))
            EventLogger.BANK_FOREGROUND -> ActivityKind.Device to res.getString(R.string.act_bank)
            EventLogger.ADB_ENABLED -> ActivityKind.Device to res.getString(R.string.act_adb)
            EventLogger.WORK_PROFILE_CREATED -> ActivityKind.Device to res.getString(R.string.act_work_profile)
            EventLogger.CLONED_APP_LAUNCHED -> ActivityKind.Device to res.getString(R.string.act_cloned)
            else -> return@forEach
        }
        items += ActivityItem(
            key = "event-${event.id}",
            timestamp = event.timestamp,
            kind = kind,
            title = title,
            detail = if (device) res.getString(R.string.act_on_phone) else event.pkg,
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
                title = res.getQuantityString(R.plurals.act_blocked, rows.size, rows.size),
                detail = pkg?.let(::name) ?: res.getString(R.string.act_app_uid, group.first),
                pkg = pkg,
            )
        }
    val since = System.currentTimeMillis() - INSTALL_HISTORY_MILLIS
    installed.filter { !it.isSystem && it.firstInstallTime >= since }.forEach { app ->
        items += ActivityItem(
            key = "install-${app.pkg}",
            timestamp = app.firstInstallTime,
            kind = if (app.sideloaded) ActivityKind.Review else ActivityKind.Device,
            title = res.getString(R.string.act_installed, app.label),
            detail = sourceText(res, app),
            pkg = app.pkg,
        )
    }
    return items.sortedByDescending { it.timestamp }
}

private const val INSTALL_HISTORY_MILLIS = 30 * DateUtils.DAY_IN_MILLIS

/** Localised name of a risk level. */
fun levelText(res: Resources, level: RiskLevel): String = res.getString(
    when (level) {
        RiskLevel.Safe -> R.string.level_safe
        RiskLevel.Review -> R.string.badge_review
        RiskLevel.High -> R.string.badge_high
        RiskLevel.Critical -> R.string.badge_critical
    },
)

/** Relative time such as "5 minutes ago", in the app language. */
fun relativeTime(res: Resources, timestamp: Long): String {
    val now = System.currentTimeMillis()
    if (now - timestamp < DateUtils.MINUTE_IN_MILLIS) return res.getString(R.string.just_now)
    return DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

private enum class ActivityFilter(val label: Int) {
    All(R.string.filter_all),
    Alerts(R.string.filter_alerts),
    Network(R.string.filter_network),
    Device(R.string.filter_device),
}

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
                Text(stringResource(R.string.nav_activity), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.activity_subtitle),
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActivityFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(stringResource(option.label)) },
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
                            Text(stringResource(R.string.activity_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.activity_empty_detail),
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
    val res = LocalContext.current.resources
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
                    "${relativeTime(res, item.timestamp)} · ${item.detail}",
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
