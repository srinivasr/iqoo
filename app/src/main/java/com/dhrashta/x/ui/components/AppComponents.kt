package com.dhrashta.x.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.ui.theme.Amber
import com.dhrashta.x.ui.theme.AmberSoft
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.Ink
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk
import com.dhrashta.x.ui.theme.Surface

val SmallRadius = 10.dp
val ScreenPadding = 20.dp

@Composable
fun QuietCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .background(Surface, RoundedCornerShape(SmallRadius))
            .border(1.dp, Line, RoundedCornerShape(SmallRadius)),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleLarge, color = Ink)
}

@Composable
fun AmberLabel(text: String) {
    Text(
        text = text,
        color = Amber,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(AmberSoft, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun AppIdentity(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickToolsIcon(Modifier.size(if (compact) 42.dp else 52.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("QuickTools", style = MaterialTheme.typography.titleMedium)
            Text("Version 2.4.1", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun QuickToolsIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFFF0EEE7), RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(25.dp)) {
            val w = size.width
            val stroke = Stroke(width = 2.3f, cap = StrokeCap.Round)
            drawLine(Ink, Offset(w * .24f, w * .26f), Offset(w * .76f, w * .74f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(Ink, w * .14f, Offset(w * .27f, w * .25f), style = stroke)
            drawCircle(Ink, w * .14f, Offset(w * .73f, w * .75f), style = stroke)
        }
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Forest),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Chevron(direction: ChevronDirection = ChevronDirection.Right, color: Color = Ink) {
    Canvas(Modifier.size(18.dp)) {
        val path = Path()
        when (direction) {
            ChevronDirection.Right -> { path.moveTo(size.width * .35f, size.height * .2f); path.lineTo(size.width * .65f, size.height * .5f); path.lineTo(size.width * .35f, size.height * .8f) }
            ChevronDirection.Down -> { path.moveTo(size.width * .2f, size.height * .35f); path.lineTo(size.width * .5f, size.height * .65f); path.lineTo(size.width * .8f, size.height * .35f) }
        }
        drawPath(path, color, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
    }
}

enum class ChevronDirection { Right, Down }

@Composable
fun ClickableRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
