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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhrashta.x.ui.components.AppIdentity
import com.dhrashta.x.ui.components.Chevron
import com.dhrashta.x.ui.components.ChevronDirection
import com.dhrashta.x.ui.components.ClickableRow
import com.dhrashta.x.ui.components.PrimaryAction
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SecondaryAction
import com.dhrashta.x.ui.components.SectionTitle
import com.dhrashta.x.ui.theme.Amber
import com.dhrashta.x.ui.theme.AmberSoft
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Danger
import com.dhrashta.x.ui.theme.DangerSoft
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk

/** Plain-language summary of why [risk] got its score; used on screen and for "Listen". */
fun explanationFor(risk: AppRisk): String = when {
    risk.trusted -> "You marked ${risk.app.label} as trusted, so DHRASHTAX does not flag it."
    risk.evidence.isEmpty() -> "${risk.app.label} shows no warning signs. It came from ${sourceText(risk.app).lowercase()} and has no sensitive access we watch for."
    else -> "${risk.app.label}: ${risk.evidence.joinToString("; ") { it.title.replaceFirstChar(Char::lowercase) }}. " +
        if (risk.level >= RiskLevel.Review) {
            "These signs do not confirm malware, but review its permissions and remove it if you do not trust it."
        } else {
            "On their own these are common and low risk."
        }
}

@Composable
fun RiskDetailsScreen(
    risk: AppRisk,
    explanation: String?,
    onBack: () -> Unit,
    onPauseInternet: () -> Unit,
    onOpenPaused: () -> Unit,
    onReviewPermissions: () -> Unit,
    onUninstall: () -> Unit,
    onTrust: (Boolean) -> Unit,
    onListen: (String) -> Unit,
    onAnalyze: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var evidenceExpanded by rememberSaveable(risk.app.pkg) { mutableStateOf(false) }
    val summary = explanation ?: explanationFor(risk)
    Column(
        Modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
    ) {
        ScreenHeader("App details", onBack)
        Spacer(Modifier.height(12.dp))
        AppIdentity(risk.app)
        Text(
            sourceText(risk.app),
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 64.dp, top = 4.dp),
        )
        Spacer(Modifier.height(22.dp))
        VerdictBanner(risk)
        Spacer(Modifier.height(20.dp))
        Text(
            "${if (risk.fullAnalysis) "Full on-device analysis" else "Quick check"} · risk score ${risk.score} of 100" +
                (risk.lastReviewed?.let { " · ${relativeTime(it)}" } ?: ""),
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(22.dp))
        SectionTitle("What we found")
        Spacer(Modifier.height(12.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (risk.evidence.isEmpty()) {
                    EvidenceItem("No warning signs", "Nothing in its install source, permissions or behaviour stood out.", Forest)
                }
                risk.evidence.forEachIndexed { index, evidence ->
                    if (index > 0) HorizontalDivider(color = Line)
                    EvidenceItem(evidence.title, evidence.detail, if (evidence.weight >= 20) Danger else if (evidence.weight >= 10) Amber else Forest)
                }
            }
        }
        if (risk.evidence.isNotEmpty() && risk.level >= RiskLevel.Review) {
            Text(
                "These signs do not confirm malware.",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        ClickableRow({ onListen(summary) }) {
            LineIcon(LineIconType.Speaker, Modifier.size(22.dp))
            Text("Listen to explanation", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Chevron()
        }
        HorizontalDivider(color = Line)
        ClickableRow({ evidenceExpanded = !evidenceExpanded }) {
            Text("Technical details", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chevron(if (evidenceExpanded) ChevronDirection.Down else ChevronDirection.Right)
        }
        if (evidenceExpanded) {
            TechnicalDetails(risk)
        }
        Spacer(Modifier.height(12.dp))
        if (risk.paused) {
            PrimaryAction("Internet paused · Manage", onOpenPaused)
        } else if (!risk.app.isSystem) {
            PrimaryAction("Pause internet", onPauseInternet)
        }
        Spacer(Modifier.height(10.dp))
        SecondaryAction("Review permissions", onReviewPermissions)
        if (risk.app.a11yEnabled) {
            Spacer(Modifier.height(10.dp))
            SecondaryAction("Analyze again", onAnalyze)
        }
        TextButton(onClick = { onTrust(!risk.trusted) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(if (risk.trusted) "Stop trusting this app" else "I trust this app", color = Ink, fontWeight = FontWeight.SemiBold)
        }
        if (!risk.app.isSystem) {
            TextButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                Text("Uninstall app", color = Danger, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VerdictBanner(risk: AppRisk) {
    val (text, fg, bg) = when {
        risk.trusted -> Triple("Trusted by you", Forest, ForestSoft)
        risk.level == RiskLevel.Critical -> Triple("Possible banking-malware behaviour", Danger, DangerSoft)
        risk.level == RiskLevel.High -> Triple("Risky behaviour found", Danger, DangerSoft)
        risk.level == RiskLevel.Review -> Triple("Some activity needs your attention", Amber, AmberSoft)
        risk.evidence.isNotEmpty() -> Triple("Low risk", Forest, ForestSoft)
        else -> Triple("No issues found", Forest, ForestSoft)
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(22.dp).background(fg, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (risk.level >= RiskLevel.Review && !risk.trusted) "!" else "✓", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${risk.score}", color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TechnicalDetails(risk: AppRisk) {
    val app = risk.app
    Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailLine("Package", app.pkg)
        DetailLine("App UID", app.uid.toString())
        DetailLine("Installer", app.installer ?: "Unknown")
        DetailLine("Installed", DateUtils.formatDateTime(null, app.firstInstallTime, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR))
        DetailLine("Accessibility", if (!app.a11yEnabled) "Off" else if (app.a11yIsTool) "On (declared tool)" else "On")
        DetailLine("Score", "${risk.score} (${if (risk.fullAnalysis) "RiskEngine" else "quick check"}; review ≥30, high ≥60, critical ≥90)")
        risk.lastReviewed?.let { DetailLine("Last analysed", relativeTime(it)) }
        if (risk.engineSignals.isNotEmpty()) {
            DetailLine("Engine signals", risk.engineSignals.joinToString { "${it.id} (${if (it.weight > 0) "+" else ""}${it.weight})" })
        }
        Text("All analysis was performed on this device.", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.62f))
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            LineIcon(LineIconType.Back, Modifier.size(22.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun EvidenceItem(title: String, detail: String, dot: Color) {
    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).background(dot, CircleShape))
        Column(Modifier.padding(start = 11.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
