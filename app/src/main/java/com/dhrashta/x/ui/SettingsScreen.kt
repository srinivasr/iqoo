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
import androidx.compose.ui.text.font.FontWeight
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
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk

/** Live status of every sensor and permission the Settings tab shows. Model keys are string resource IDs. */
data class SettingsStatus(
    val monitoring: Boolean,
    val vpnConsent: Boolean,
    val dnsMonitor: Boolean,
    val usageAccess: Boolean,
    val notifications: Boolean,
    val contacts: Boolean,
    val models: Map<Int, Boolean>,
)

@Composable
fun SettingsScreen(
    status: SettingsStatus,
    trustedApps: List<InstalledApp>,
    language: AppLanguage.Option,
    onHome: () -> Unit,
    onActivity: () -> Unit,
    onScanNow: () -> Unit,
    onEnableDnsMonitor: () -> Unit,
    onUsageAccess: () -> Unit,
    onNotifications: () -> Unit,
    onContacts: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onChangeLanguage: () -> Unit,
    onUntrust: (String) -> Unit,
    onOpenApp: (String) -> Unit,
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
                        color = Forest,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Chevron(color = Forest)
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.settings_protection))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingRow(R.string.set_a11y_title, R.string.set_a11y_detail, status.monitoring, R.string.set_starting, null)
                    HorizontalDivider(color = Line)
                    SettingRow(R.string.set_dns_title, R.string.set_dns_detail, status.vpnConsent && status.dnsMonitor, R.string.set_turn_on, onEnableDnsMonitor)
                    HorizontalDivider(color = Line)
                    SettingRow(R.string.set_bank_title, R.string.set_bank_detail, status.usageAccess, R.string.set_allow, onUsageAccess)
                    HorizontalDivider(color = Line)
                    SettingRow(R.string.set_decoy_title, R.string.set_decoy_detail, status.contacts, R.string.set_allow, onContacts)
                    HorizontalDivider(color = Line)
                    SettingRow(R.string.set_alerts_title, R.string.set_alerts_detail, status.notifications, R.string.set_allow, onNotifications)
                }
            }
            Text(
                stringResource(R.string.set_open_a11y),
                color = Forest,
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
                                color = Forest,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onUntrust(app.pkg) }.padding(8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle(stringResource(R.string.set_ai))
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    status.models.entries.forEachIndexed { index, (name, installed) ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusPill(stringResource(if (installed) R.string.model_installed else R.string.model_missing), installed)
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.set_ai_note),
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )

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
private fun SettingRow(title: Int, detail: Int, on: Boolean, actionLabel: Int, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(detail), color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
        if (on || onAction == null) {
            StatusPill(stringResource(if (on) R.string.set_on else actionLabel), on)
        } else {
            Text(
                stringResource(actionLabel),
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Forest, RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
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
