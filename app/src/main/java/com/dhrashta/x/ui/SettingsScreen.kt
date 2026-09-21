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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.BuildConfig
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

/** Live status of every sensor and permission the Settings tab shows. */
data class SettingsStatus(
    val monitoring: Boolean,
    val vpnConsent: Boolean,
    val dnsMonitor: Boolean,
    val usageAccess: Boolean,
    val notifications: Boolean,
    val contacts: Boolean,
    val models: Map<String, Boolean>,
)

/** Explanation languages LlmExplainer supports; the stored value is what it matches on. */
val EXPLANATION_LANGUAGES = listOf("English" to "English", "Hindi" to "हिन्दी", "Bengali" to "বাংলা")

@Composable
fun SettingsScreen(
    status: SettingsStatus,
    trustedApps: List<InstalledApp>,
    language: String,
    onHome: () -> Unit,
    onActivity: () -> Unit,
    onScanNow: () -> Unit,
    onEnableDnsMonitor: () -> Unit,
    onUsageAccess: () -> Unit,
    onNotifications: () -> Unit,
    onContacts: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onLanguage: (String) -> Unit,
    onUntrust: (String) -> Unit,
    onOpenApp: (String) -> Unit,
) {
    BackHandler(onBack = onHome)
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding)) {
            Spacer(Modifier.height(26.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Turn on each protection below. Everything runs on this phone.",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(20.dp))
            PrimaryAction("Scan apps now", onScanNow)

            Spacer(Modifier.height(30.dp))
            SectionTitle("Protection")
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingRow("Accessibility monitoring", "Checks every newly enabled accessibility service.", status.monitoring, "Starting", null)
                    HorizontalDivider(color = Line)
                    SettingRow(
                        "Network beacon detection",
                        "Watches DNS lookups for apps that call home on a timer. Uses a local VPN.",
                        status.vpnConsent && status.dnsMonitor,
                        "Turn on",
                        onEnableDnsMonitor,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow(
                        "Banking app watch",
                        "Notices when a banking app opens, to catch data grabbed at that moment. Needs Usage access.",
                        status.usageAccess,
                        "Allow",
                        onUsageAccess,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow(
                        "Decoy contact",
                        "Adds a fake \"DHRASHTA Canary\" contact that only a data thief would send out.",
                        status.contacts,
                        "Allow",
                        onContacts,
                    )
                    HorizontalDivider(color = Line)
                    SettingRow("Threat alerts", "Notifications when a risky app is found.", status.notifications, "Allow", onNotifications)
                }
            }
            Text(
                "Open accessibility settings",
                color = Forest,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onAccessibilitySettings).padding(vertical = 14.dp),
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Trusted apps")
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (trustedApps.isEmpty()) {
                        Text(
                            "No trusted apps. Mark an app as trusted from its details screen.",
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
                                "Remove",
                                color = Forest,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onUntrust(app.pkg) }.padding(8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle("Explanation language")
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 6.dp)) {
                    EXPLANATION_LANGUAGES.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onLanguage(value) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = language == value,
                                onClick = { onLanguage(value) },
                                colors = RadioButtonDefaults.colors(selectedColor = Forest),
                            )
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionTitle("On-device AI")
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    status.models.entries.forEachIndexed { index, (name, installed) ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusPill(if (installed) "Installed" else "Not installed", installed)
                        }
                    }
                }
            }
            Text(
                "Without a model file, that feature falls back to rules only.",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(Modifier.height(30.dp))
            SectionTitle("About")
            Spacer(Modifier.height(12.dp))
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("DHRASHTAX ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "On-device threat detection. No app data leaves this phone.",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomNavigation(active = BottomDestination.Settings, onHome = onHome, onActivity = onActivity, onSettings = {})
    }
}

@Composable
private fun SettingRow(title: String, detail: String, on: Boolean, actionLabel: String, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
        if (on || onAction == null) {
            StatusPill(if (on) "On" else actionLabel, on)
        } else {
            Text(
                actionLabel,
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
