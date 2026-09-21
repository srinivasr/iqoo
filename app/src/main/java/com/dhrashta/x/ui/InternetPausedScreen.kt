package com.dhrashta.x.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhrashta.x.R
import com.dhrashta.x.ui.components.AppIdentity
import com.dhrashta.x.ui.components.QuietCard
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.components.SecondaryAction
import com.dhrashta.x.ui.components.SectionTitle
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Info
import com.dhrashta.x.ui.theme.InfoSoft
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk

@Composable
fun InternetPausedScreen(
    risk: AppRisk,
    blockedCount: Int,
    lastBlocked: Long?,
    onBack: () -> Unit,
    onResumeInternet: () -> Unit,
    onReviewPermissions: () -> Unit,
    onUninstall: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Canvas).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding)) {
        ScreenHeader(stringResource(R.string.paused_header), onBack)
        Spacer(Modifier.height(12.dp))
        AppIdentity(risk.app)
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(48.dp).background(ForestSoft, CircleShape), contentAlignment = Alignment.Center) {
            LineIcon(LineIconType.Check, Modifier.size(25.dp), Forest)
        }
        Text(stringResource(R.string.paused_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            stringResource(R.string.paused_message, risk.app.label),
            color = MutedInk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 9.dp),
        )
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth().background(InfoSoft, RoundedCornerShape(8.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(21.dp).background(Info, CircleShape), contentAlignment = Alignment.Center) {
                Text("i", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.paused_others), color = Info, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(30.dp))
        SectionTitle(stringResource(R.string.paused_next))
        Spacer(Modifier.height(12.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                NextStep("1", stringResource(R.string.paused_step_review))
                HorizontalDivider(color = Line)
                NextStep("2", stringResource(R.string.paused_step_remove))
            }
        }
        Text(
            stringResource(R.string.paused_note),
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
        SecondaryAction(stringResource(R.string.action_resume_internet), onResumeInternet)
        Spacer(Modifier.height(10.dp))
        SecondaryAction(stringResource(R.string.action_review_permissions), onReviewPermissions)
        Spacer(Modifier.height(10.dp))
        SecondaryAction(stringResource(R.string.action_uninstall), onUninstall)
        Spacer(Modifier.height(30.dp))
        SectionTitle(stringResource(R.string.nav_activity))
        Spacer(Modifier.height(12.dp))
        QuietCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(Forest, CircleShape))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        if (blockedCount == 0) {
                            stringResource(R.string.paused_no_attempts)
                        } else {
                            pluralStringResource(R.plurals.paused_attempts_blocked, blockedCount, blockedCount)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        lastBlocked?.let { stringResource(R.string.paused_last_attempt, relativeTime(LocalContext.current.resources, it)) }
                            ?: stringResource(R.string.paused_attempts_hint),
                        color = MutedInk,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NextStep(number: String, text: String) {
    Row(Modifier.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(25.dp).background(ForestSoft, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = Forest, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
    }
}
