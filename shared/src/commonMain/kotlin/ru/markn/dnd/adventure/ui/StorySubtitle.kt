package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.markn.dnd.adventure.SUBTITLE_CHARACTER_MILLIS

/** One transparent subtitle style for every speaker, including cinematic narration. */
@Composable
internal fun StorySubtitle(text: String, speaker: String?, elapsedMillis: Long, modifier: Modifier = Modifier) {
    val scale = LocalGamePreferences.current?.subtitleScale ?: 1f
    val count = (elapsedMillis.coerceAtLeast(0) / SUBTITLE_CHARACTER_MILLIS).coerceIn(0, text.length.toLong()).toInt()
    // Do not expose a lone UTF-16 high surrogate while revealing a character.
    val end = if (count > 0 && text[count - 1].isHighSurrogate()) count - 1 else count
    Column(modifier.widthIn(max = 900.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!speaker.isNullOrBlank()) Text(speaker, Modifier.fillMaxWidth(), color = AdventureGold,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(Color.Black, Offset(0f, 2f), 5f)))
        // Lay out the complete phrase from the first frame. Revealing a growing prefix
        // re-centres every line and moves the whole bottom-aligned block on word wrap.
        val revealedText = buildAnnotatedString {
            append(text)
            if (end < text.length) addStyle(
                SpanStyle(color = Color.Transparent, shadow = Shadow(Color.Transparent)),
                end, text.length,
            )
        }
        Text(revealedText, Modifier.fillMaxWidth(), color = Color.White, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = (21 * scale).sp, lineHeight = (29 * scale).sp,
                shadow = Shadow(Color.Black, Offset(0f, 2f), 6f)))
    }
}
