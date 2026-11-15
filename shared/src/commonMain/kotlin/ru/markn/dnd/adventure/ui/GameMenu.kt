package ru.markn.dnd.adventure.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.russhwolf.settings.Settings
import kotlin.math.roundToInt
import ru.markn.dnd.generated.resources.*

class GamePreferences(private val storage: Settings? = runCatching { Settings() }.getOrNull()) {
    var motion by mutableStateOf(runCatching { storage?.getBoolean("ui.motion", true) ?: true }.getOrDefault(true))
        private set
    fun changeMotion(value: Boolean) { motion = value; runCatching { storage?.putBoolean("ui.motion", value) } }
    var speechEnabled by mutableStateOf(runCatching { storage?.getBoolean("speech.enabled", true) ?: true }.getOrDefault(true))
        private set
    var speechVolume by mutableFloatStateOf(readVolume("speech.volume", .9f))
        private set
    var subtitleScale by mutableFloatStateOf(runCatching { storage?.getFloat("ui.subtitleScale", 1f) ?: 1f }
        .getOrDefault(1f).let { if (it.isFinite()) it.coerceIn(.8f, 1.4f) else 1f })
        private set
    private fun readVolume(key: String, fallback: Float) = runCatching { storage?.getFloat(key, fallback) ?: fallback }
        .getOrDefault(fallback).let { if (it.isFinite()) it.coerceIn(0f, 1f) else fallback }
    fun changeSpeech(value: Boolean) { speechEnabled = value; runCatching { storage?.putBoolean("speech.enabled", value) } }
    fun changeSpeechVolume(value: Float) {
        if (!value.isFinite()) return
        speechVolume = value.coerceIn(0f, 1f)
        runCatching { storage?.putFloat("speech.volume", speechVolume) }
    }
    fun changeSubtitleScale(value: Float) {
        if (!value.isFinite()) return
        subtitleScale = value.coerceIn(.8f, 1.4f)
        runCatching { storage?.putFloat("ui.subtitleScale", subtitleScale) }
    }
}

val LocalGameMotion = staticCompositionLocalOf { true }
val LocalGamePreferences = staticCompositionLocalOf<GamePreferences?> { null }

@Composable
fun GameBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        MovingStoryImage(Res.drawable.menu_campfire, "Костёр над речной долиной", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20A131A), Color(0xC0101920), Color(0x70101920)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9081016)))))
        content()
    }
}

@Composable
fun MenuHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onBack) { Text("‹  Назад") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AdventureMuted)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = AdventureGold.copy(alpha = .3f))
}

@Composable
fun MainMenu(onCreate: () -> Unit, onJoin: () -> Unit, onCharacter: () -> Unit, onSettings: () -> Unit, onExit: (() -> Unit)?) {
    GameBackdrop {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compact = maxWidth < 700.dp
            Column(Modifier.fillMaxHeight().widthIn(max = 620.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 24.dp else 64.dp, vertical = 36.dp), verticalArrangement = Arrangement.Center) {
                Text("СОВМЕСТНОЕ ПРИКЛЮЧЕНИЕ", color = AdventureGold, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(18.dp))
                Text("Истории\nу костра", style = if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(16.dp))
                Text("Соберите друзей. Выберите свой путь.\nСтаньте героями одной истории.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(36.dp))
                MenuEntry("01", "Создать игру", "Выбрать историю и собрать отряд", true, onCreate)
                MenuEntry("02", "Присоединиться к игре", "Продолжить путь вместе с друзьями", false, onJoin)
                MenuEntry("03", "Персонаж", "Ваш герой и его облик", false, onCharacter)
                MenuEntry("04", "Настройки", "Звук, субтитры и движение камеры", false, onSettings)
                if (onExit != null) MenuEntry("05", "Выход", "", false, onExit)
                Spacer(Modifier.height(30.dp))
                Text("2–4 ИГРОКА   ·   40–60 МИНУТ   ·   ВАША ИСТОРИЯ", color = AdventureMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MenuEntry(number: String, title: String, subtitle: String, primary: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val emphasis by animateFloatAsState(if (hovered || focused || pressed) 1f else 0f, tween(180), label = "menu highlight")
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 60.dp),
        interactionSource = interaction,
        shape = MaterialTheme.shapes.small, color = if (primary) AdventureGold.copy(alpha = .18f + emphasis * .12f) else MaterialTheme.colorScheme.surface.copy(alpha = .5f + emphasis * .4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AdventureGold.copy(alpha = if (primary) .65f + emphasis * .3f else .16f + emphasis * .6f))) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(number, color = AdventureGold, style = MaterialTheme.typography.labelSmall)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AdventureMuted)
            }
            Text("›", color = AdventureGold, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
fun GameSettings(preferences: GamePreferences, onBack: () -> Unit, onLocal: () -> Unit, onCombat: () -> Unit) {
    GameBackdrop {
        Column(Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth().align(Alignment.TopCenter)
            .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MenuHeader("Настройки", "Сохраняются на этом устройстве", onBack)
            GameSettingsContent(preferences)
            HorizontalDivider()
            Text("Игра на одном устройстве", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onLocal) { Text("Локальное приключение") }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onCombat) { Text("Учебный бой") }
            }
        }
    }
}

@Composable
internal fun GameSettingsContent(preferences: GamePreferences, speech: StorySpeech? = null) {
    val music = LocalStoryMusic.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Звук", style = MaterialTheme.typography.titleLarge)
        SettingsSection {
            SettingsToggle("Озвучка", "Голоса рассказчика, героев и собеседников", preferences.speechEnabled) {
                if (speech != null) speech.toggle() else preferences.changeSpeech(it)
            }
            VolumeSetting("Громкость озвучки", preferences.speechVolume, preferences.speechEnabled) {
                if (speech != null) speech.loudness(it) else preferences.changeSpeechVolume(it)
            }
            if (speech?.player == null && speech != null) Text("Звук недоступен на этом устройстве. История продолжится с субтитрами.", color = AdventureMuted)
            speech?.status?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AdventureMuted) }
            if (music != null) {
                HorizontalDivider()
                SettingsToggle("Музыка", "Фоновая музыка меню и приключения", music.enabled) { music.toggle() }
                VolumeSetting("Громкость музыки", music.volume, music.enabled, music::setLoudness)
                val playback = music.player?.state?.collectAsState()?.value
                if (music.player == null) Text("Музыкальный плеер недоступен на этом устройстве.", color = AdventureMuted)
                if (playback is io.github.nadeemiqbal.soundscape.core.PlaybackState.Error)
                    Text("Не удалось воспроизвести музыку. Попробуйте включить звук снова.", color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(shape = MaterialTheme.shapes.small, enabled = preferences.speechEnabled || music?.enabled == true,
                onClick = { music?.resume(force = true); speech?.resume() }) { Text("Включить звук") }
            Text("Если браузер заблокировал воспроизведение, нажмите «Включить звук».", color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text("Отображение", style = MaterialTheme.typography.titleLarge)
        SettingsSection {
            Text("Размер субтитров · ${(preferences.subtitleScale * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(preferences.subtitleScale, preferences::changeSubtitleScale, valueRange = .8f..1.4f, steps = 5,
                modifier = Modifier.semantics { contentDescription = "Размер субтитров" })
            StorySubtitle("Костёр потрескивает. Впереди — новая история.", "Рассказчик", Long.MAX_VALUE,
                Modifier.fillMaxWidth())
            HorizontalDivider()
            SettingsToggle("Движение камеры", "Плавное приближение фонов в меню и истории", preferences.motion, preferences::changeMotion)
        }
    }
}

@Composable
private fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .92f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = AdventureMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun VolumeSetting(title: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Text("$title · ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
    Slider(value, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
}

@Composable
fun GameSettingsButton() {
    val preferences = LocalGamePreferences.current ?: return
    val speech = LocalStorySpeech.current
    var open by remember { mutableStateOf(false) }
    TextButton(shape = MaterialTheme.shapes.small, onClick = { open = true }) { Text("Настройки") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Настройки") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { GameSettingsContent(preferences, speech) } },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Готово") } })
}
