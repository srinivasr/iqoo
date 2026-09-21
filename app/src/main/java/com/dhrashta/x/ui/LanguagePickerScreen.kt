package com.dhrashta.x.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhrashta.x.R
import com.dhrashta.x.data.AppLanguage
import com.dhrashta.x.ui.components.PrimaryAction
import com.dhrashta.x.ui.components.ScreenPadding
import com.dhrashta.x.ui.theme.Canvas
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Line
import com.dhrashta.x.ui.theme.MutedInk
import com.dhrashta.x.ui.theme.Surface

/**
 * Full-screen language list, each language written in its own script. Tapping a card applies the
 * language immediately (the screen redraws in it); Continue confirms. [onBack] is null on first launch.
 */
@Composable
fun LanguagePickerScreen(
    selectedTag: String,
    onSelect: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Canvas)) {
        if (onBack != null) {
            Box(Modifier.padding(horizontal = ScreenPadding)) { ScreenHeader("", onBack) }
        } else {
            Spacer(Modifier.height(36.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = ScreenPadding)) {
            item {
                Text(stringResource(R.string.app_name), color = Forest, fontSize = 15.sp, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.lang_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.lang_subtitle),
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
            }
            items(AppLanguage.OPTIONS, key = { it.tag }) { option ->
                LanguageCard(option, option.tag == selectedTag) { onSelect(option.tag) }
                Spacer(Modifier.height(10.dp))
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Box(Modifier.fillMaxWidth().background(Surface).padding(horizontal = ScreenPadding, vertical = 14.dp).navigationBarsPadding()) {
            PrimaryAction(stringResource(R.string.lang_continue), onContinue)
        }
    }
}

@Composable
private fun LanguageCard(option: AppLanguage.Option, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) ForestSoft else Surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Forest else Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.nativeName,
                fontSize = 20.sp,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            )
            if (option.nativeName != option.englishName) {
                Text(option.englishName, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Box(
            Modifier.size(24.dp).border(2.dp, if (selected) Forest else Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                LineIcon(LineIconType.Check, Modifier.size(16.dp), Forest)
            }
        }
    }
}
