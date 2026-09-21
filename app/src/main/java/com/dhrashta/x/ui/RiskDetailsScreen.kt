package com.dhrashta.x.ui

import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhrashta.x.R
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
fun explanationFor(res: Resources, risk: AppRisk): String = when {
    risk.trusted -> res.getString(R.string.explain_trusted, risk.app.label)
    risk.evidence.isEmpty() -> res.getString(R.string.explain_clean, risk.app.label, sourceText(res, risk.app))
    else -> res.getString(
        R.string.explain_findings,
        risk.app.label,
        risk.evidence.joinToString(res.getString(R.string.explain_separator)) { it.title.replaceFirstChar(Char::lowercase) },
    ) + " " + res.getString(if (risk.level >= RiskLevel.Review) R.string.explain_review_advice else R.string.explain_low_advice)
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
    val res = LocalContext.current.resources
    val summary = explanation ?: explanationFor(res, risk)
    Column(
        Modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
    ) {
        ScreenHeader(stringResource(R.string.details_title), onBack)
        Spacer(Modifier.height(12.dp))
        AppIdentity(risk.app)
        Text(
            sourceText(res, risk.app),
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 64.dp, top = 4.dp),
        )
        Spacer(Modifier.height(22.dp))
        VerdictBanner(risk)
        Spacer(Modifier.height(20.dp))
        val method = stringResource(if (risk.fullAnalysis) R.string.details_full_analysis else R.string.details_quick_check)
        Text(
            risk.lastReviewed?.let { stringResource(R.string.details_score_line_time, method, risk.score, relativeTime(res, it)) }
                ?: stringResource(R.string.details_score_line, method, risk.score),
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(22.dp))
        SectionTitle(stringResource(R.string.details_what_we_found))
        Spacer(Modifier.height(12.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (risk.evidence.isEmpty()) {
                    EvidenceItem(stringResource(R.string.details_no_warning_title), stringResource(R.string.details_no_warning_detail), Forest)
                }
                risk.evidence.forEachIndexed { index, evidence ->
                    if (index > 0) HorizontalDivider(color = Line)
                    EvidenceItem(evidence.title, evidence.detail, if (evidence.weight >= 20) Danger else if (evidence.weight >= 10) Amber else Forest)
                }
            }
        }
        if (risk.evidence.isNotEmpty() && risk.level >= RiskLevel.Review) {
            Text(
                stringResource(R.string.details_not_confirm),
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        ClickableRow({ onListen(summary) }) {
            LineIcon(LineIconType.Speaker, Modifier.size(22.dp))
            Text(stringResource(R.string.details_listen), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Chevron()
        }
        HorizontalDivider(color = Line)
        ClickableRow({ evidenceExpanded = !evidenceExpanded }) {
            Text(stringResource(R.string.details_technical), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chevron(if (evidenceExpanded) ChevronDirection.Down else ChevronDirection.Right)
        }
        if (evidenceExpanded) {
            TechnicalDetails(risk)
        }
        Spacer(Modifier.height(12.dp))
        if (risk.paused) {
            PrimaryAction(stringResource(R.string.action_paused_manage), onOpenPaused)
        } else if (!risk.app.isSystem) {
            PrimaryAction(stringResource(R.string.action_pause_internet), onPauseInternet)
        }
        Spacer(Modifier.height(10.dp))
        SecondaryAction(stringResource(R.string.action_review_permissions), onReviewPermissions)
        if (risk.app.a11yEnabled) {
            Spacer(Modifier.height(10.dp))
            SecondaryAction(stringResource(R.string.action_analyze_again), onAnalyze)
        }
        TextButton(onClick = { onTrust(!risk.trusted) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(stringResource(if (risk.trusted) R.string.action_untrust else R.string.action_trust), color = Ink, fontWeight = FontWeight.SemiBold)
        }
        if (!risk.app.isSystem) {
            TextButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_uninstall), color = Danger, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VerdictBanner(risk: AppRisk) {
    val (textRes, fg, bg) = when {
        risk.trusted -> Triple(R.string.verdict_trusted, Forest, ForestSoft)
        risk.level == RiskLevel.Critical -> Triple(R.string.verdict_critical, Danger, DangerSoft)
        risk.level == RiskLevel.High -> Triple(R.string.verdict_high, Danger, DangerSoft)
        risk.level == RiskLevel.Review -> Triple(R.string.verdict_review, Amber, AmberSoft)
        risk.evidence.isNotEmpty() -> Triple(R.string.badge_low, Forest, ForestSoft)
        else -> Triple(R.string.level_safe, Forest, ForestSoft)
    }
    val text = stringResource(textRes)
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
    val context = LocalContext.current
    val res = context.resources
    Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailLine(stringResource(R.string.tech_package), app.pkg)
        DetailLine(stringResource(R.string.tech_uid), app.uid.toString())
        DetailLine(stringResource(R.string.tech_installer), app.installer ?: stringResource(R.string.tech_unknown))
        DetailLine(
            stringResource(R.string.tech_installed),
            DateUtils.formatDateTime(context, app.firstInstallTime, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
        )
        DetailLine(
            stringResource(R.string.tech_accessibility),
            stringResource(if (!app.a11yEnabled) R.string.tech_a11y_off else if (app.a11yIsTool) R.string.tech_a11y_tool else R.string.tech_a11y_on),
        )
        DetailLine(
            stringResource(R.string.tech_score),
            stringResource(R.string.tech_score_value, risk.score, if (risk.fullAnalysis) "RiskEngine" else stringResource(R.string.details_quick_check)),
        )
        risk.lastReviewed?.let { DetailLine(stringResource(R.string.tech_last_analysed), relativeTime(res, it)) }
        if (risk.engineSignals.isNotEmpty()) {
            DetailLine(stringResource(R.string.tech_engine_signals), risk.engineSignals.joinToString { "${it.id} (${if (it.weight > 0) "+" else ""}${it.weight})" })
        }
        Text(stringResource(R.string.tech_on_device), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
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
        val backLabel = stringResource(R.string.cd_back)
        Box(
            Modifier.size(44.dp).clickable(onClick = onBack).semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
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
