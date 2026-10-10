package com.dhrashta.x.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.BuildConfig
import com.dhrashta.x.R
import com.dhrashta.x.data.AppLanguage
import com.dhrashta.x.ui.components.Chevron
import com.dhrashta.x.ui.components.PrimaryAction
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SectionTitle
import com.dhrashta.x.ui.theme.Amber
import com.dhrashta.x.ui.theme.AmberSoft
import com.dhrashta.x.ui.theme.Accent
import com.dhrashta.x.ui.theme.AccentSoft
import com.dhrashta.x.ui.theme.AppThemeMode
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Surface

/** Whether a sensor is running, switched off, or simply not something this build can see. */
enum class CoverageState { On, Off, NotMonitored }

/** One row of the "What is monitored" card. Everything here is read from live state. */
data class CoverageItem(
    val title: String,
    val detail: String,
    val state: CoverageState,
    val onToggle: (() -> Unit)? = null,
)

/** Live status of every sensor and permission the Settings tab shows. */
data class SettingsStatus(
    val monitoring: Boolean,
    val vpnConsent: Boolean,
    val dnsMonitor: Boolean,
    val usageAccess: Boolean,
    val notifications: Boolean,
    val decoys: Boolean,
    val coverage: List<CoverageItem>,
)

@Composable
fun SettingsScreen(
    status: SettingsStatus,
    trustedApps: List<InstalledApp>,
    language: AppLanguage.Option,
    themeMode: AppThemeMode,
    audioSettings: com.dhrashta.x.data.AudioSettings = com.dhrashta.x.data.AudioSettings(),
    onPaceChange: (com.dhrashta.x.data.SpeechPace) -> Unit = {},
    onSpokenAlertsChange: (Boolean) -> Unit = {},
    onThemeChange: (AppThemeMode) -> Unit,
    onHome: () -> Unit,
    onActivity: () -> Unit,
    onScanNow: () -> Unit,
    onEnableDnsMonitor: () -> Unit,
    onUsageAccess: () -> Unit,
    onNotifications: () -> Unit,
    onDecoys: () -> Unit,
    onSelfTest: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onChangeLanguage: () -> Unit,
    onUntrust: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    autoContain: Boolean = false,
    onAutoContainChange: (Boolean) -> Unit = {},
    onPolicies: () -> Unit = {},
) {
    BackHandler(onBack = onHome)
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding)) {
            Spacer(Modifier.height(26.dp))
            Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.settings_subtitle),
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(20.dp))
            PrimaryAction(stringResource(R.string.action_scan_now), onScanNow)

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.theme_title))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp))
                    .padding(4.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val selected = mode == themeMode
                    Text(
                        stringResource(if (mode == AppThemeMode.Dark) R.string.theme_dark else R.string.theme_light),
                        modifier = Modifier.weight(1f)
                            .background(if (selected) AccentSoft else Surface, RoundedCornerShape(9.dp))
                            .selectable(selected = selected, role = Role.RadioButton) { onThemeChange(mode) }
                            .padding(vertical = 12.dp),
                        color = if (selected) Accent else Ink,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.set_language))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth().clickable(onClick = onChangeLanguage)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(language.nativeName, style = MaterialTheme.typography.titleMedium)
                        Text(language.englishName, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        stringResource(R.string.set_change),
                        color = Accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chevron(color = Accent)
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.settings_audio_title))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(stringResource(R.string.set_speech_pace), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(10.dp)).padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            com.dhrashta.x.data.SpeechPace.entries.forEach { pace ->
                                val selected = pace == audioSettings.pace
                                Text(
                                    stringResource(pace.labelRes),
                                    modifier = Modifier.weight(1f)
                                        .background(if (selected) AccentSoft else Canvas, RoundedCornerShape(8.dp))
                                        .clickable { onPaceChange(pace) }
                                        .padding(vertical = 8.dp),
                                    color = if (selected) Accent else Ink,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                    SettingRow(
                        title = R.string.set_spoken_alerts_title,
                        detail = R.string.set_spoken_alerts_detail,
                        on = audioSettings.spokenAlertsEnabled,
                        onToggle = { onSpokenAlertsChange(!audioSettings.spokenAlertsEnabled) },
                    )
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.settings_protection))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingRow(
                        title = R.string.set_a11y_title,
                        detail = R.string.set_a11y_detail,
                        on = status.monitoring,
                        onToggle = onAccessibilitySettings,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow(
                        title = R.string.set_dns_title,
                        detail = R.string.set_dns_detail,
                        on = status.vpnConsent && status.dnsMonitor,
                        onToggle = onEnableDnsMonitor,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow(
                        title = R.string.set_bank_title,
                        detail = R.string.set_bank_detail,
                        on = status.usageAccess,
                        onToggle = onUsageAccess,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow(
                        title = R.string.set_decoy_title,
                        detail = R.string.set_decoy_detail,
                        on = status.decoys,
                        onToggle = onDecoys,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow(
                        title = R.string.set_alerts_title,
                        detail = R.string.set_alerts_detail,
                        on = status.notifications,
                        onToggle = onNotifications,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow(
                        title = R.string.set_auto_contain_title,
                        detail = R.string.set_auto_contain_detail,
                        on = autoContain,
                        onToggle = { onAutoContainChange(!autoContain) },
                    )
                }
            }
            Text(
                stringResource(R.string.set_open_a11y),
                color = Accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onAccessibilitySettings).padding(vertical = 14.dp),
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.set_trusted))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (trustedApps.isEmpty()) {
                        Text(
                            stringResource(R.string.set_trusted_empty),
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    trustedApps.forEachIndexed { index, app ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenApp(app.pkg) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.pkg, app.label, Modifier.size(36.dp))
                            Text(app.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                            Text(
                                stringResource(R.string.set_remove),
                                color = Accent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onUntrust(app.pkg) }.padding(8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.cov_title))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    status.coverage.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (item.onToggle != null) Modifier.clickable(onClick = item.onToggle) else Modifier)
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text(item.detail, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (item.onToggle != null && (item.state == CoverageState.On || item.state == CoverageState.Off)) {
                                androidx.compose.material3.Switch(
                                    checked = item.state == CoverageState.On,
                                    onCheckedChange = { item.onToggle.invoke() },
                                    colors = androidx.compose.material3.SwitchDefaults.colors(
                                        checkedThumbColor = Canvas,
                                        checkedTrackColor = Forest,
                                        uncheckedThumbColor = MutedInk,
                                        uncheckedTrackColor = Line,
                                    ),
                                )
                            } else {
                                StatusPill(
                                    stringResource(
                                        when (item.state) {
                                            CoverageState.On -> R.string.set_on
                                            CoverageState.Off -> R.string.cov_off
                                            CoverageState.NotMonitored -> R.string.cov_not_monitored
                                        },
                                    ),
                                    item.state == CoverageState.On,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.set_selftest),
                color = Accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onSelfTest).padding(vertical = 14.dp),
            )
            Text(
                stringResource(R.string.cov_note),
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(30.dp))
            SectionTitle("Compliance & Policies")
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth().clickable(onClick = onPolicies)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Terms, Policies & Permissions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Zero-telemetry audit, DPDP Act 2023 compliance & permission transparency",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        "Review",
                        color = Accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chevron(color = Accent)
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.set_about))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.about_text), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomNavigation(active = BottomDestination.Settings, onHome = onHome, onActivity = onActivity, onSettings = {})
    }
}

@Composable
private fun SettingRow(
    title: Int,
    detail: Int,
    on: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(detail), color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = { onToggle() },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Canvas,
                checkedTrackColor = Forest,
                uncheckedThumbColor = MutedInk,
                uncheckedTrackColor = Line,
            ),
        )
    }
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    Row(
        Modifier.background(if (positive) ForestSoft else AmberSoft, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (positive) Forest else Amber, CircleShape))
        Text(
            text,
            color = if (positive) Forest else Amber,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}
