package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.painterResource
import ru.markn.dnd.adventure.*

@Composable
fun CharacterPicker(library: CharacterLibrary, selected: CharacterProfile?, enabled: Boolean = true,
                    unavailableNames: List<String> = emptyList(), selectionConfirmed: Boolean = true, onSelect: (CharacterProfile) -> Unit) {
    var toRemove by remember { mutableStateOf<CharacterProfile?>(null) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<CharacterProfile?>(null) }
    AdventureSectionTitle("Выбор героя")
    selected?.let {
        Text("${if (selectionConfirmed) "Выбран" else "Предложен"}: ${it.name} · ${it.race.title} · ${it.characterClass.title} · ${it.background.title}",
            color = AdventureMuted, style = MaterialTheme.typography.bodyMedium)
    }
    listOf("Готовые герои" to ReadyCharacters.all, "Мои герои" to library.characters).forEach { (title, profiles) ->
        if (profiles.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            profiles.forEach { profile ->
                val custom = profile in library.characters
                val taken = unavailableNames.any { it.equals(profile.name, true) }
                AdventurePanel(Modifier.widthIn(max = 460.dp).fillMaxWidth(), emphasized = profile == selected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CharacterPortrait(profile, Modifier.size(68.dp).clip(MaterialTheme.shapes.small))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge)
                            Text("${profile.race.title} · ${profile.characterClass.title}", style = MaterialTheme.typography.bodySmall, color = AdventureMuted)
                            Text("${12 + profile.modifier(Ability.CONSTITUTION)} здоровья · ${profile.background.title}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // Actions use the whole card width, including on narrow touch layouts.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AdventureButton(onClick = { onSelect(profile) }, enabled = enabled && !taken && (profile != selected || !selectionConfirmed)) {
                            Text(if (taken) "Уже в группе" else if (profile == selected && selectionConfirmed) "Выбран" else "Выбрать")
                        }
                        if (custom) AdventureButton(secondary = true, onClick = { original = profile; editing = true }, enabled = enabled) { Text("Настроить") }
                        if (custom) TextButton(shape = MaterialTheme.shapes.small, onClick = { toRemove = profile }, enabled = enabled) { Text("Удалить") }
                    }
                }
            }
        }
    }
    library.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    AdventureButton(secondary = true, onClick = { original = null; editing = true }, enabled = enabled && library.characters.size < CharacterLibrary.MAX_CHARACTERS) { Text("+  Создать своего персонажа") }
    Text("Герои сохраняются на этом устройстве и доступны в разных историях.", style = MaterialTheme.typography.bodySmall, color = AdventureMuted)
    if (editing) CharacterEditor(original, onDismiss = { editing = false }, onSave = { profile ->
        if (original?.let { library.update(it, profile) } ?: library.save(profile)) { editing = false; onSelect(profile) }
    }, error = library.error)
    toRemove?.let { profile -> AdventureDialog(onDismissRequest = { toRemove = null },
        title = "Удалить ${profile.name}?", text = { Text("Герой исчезнет из сохранённых. Выбор в текущей партии сохранится.") },
        confirmButton = { AdventureButton(secondary = true, onClick = { library.remove(profile); toRemove = null }) { Text("Удалить") } },
        dismissButton = { AdventureButton(onClick = { toRemove = null }) { Text("Отмена") } }) }
}

@Composable
fun CharacterSummary(profile: CharacterProfile) {
    Text("${profile.race.title} · ${profile.characterClass.title} · ${profile.background.title}")
    Text(Ability.entries.joinToString(" · ") { "${it.abbreviation} ${profile.scores.getValue(it)} (${signedBonus(profile.modifier(it))})" },
        style = MaterialTheme.typography.bodySmall)
    Text("Здоровье: ${12 + profile.modifier(Ability.CONSTITUTION)}. ${profile.description}", style = MaterialTheme.typography.bodySmall)
    Text("Класс +1: ${profile.characterClass.domains.joinToString { it.title }}. Предыстория +1: ${profile.background.domain.title}. Не складываются.", style = MaterialTheme.typography.bodySmall)
    Text("${profile.race.talent}: +2 один раз за историю — ${profile.race.domains.joinToString { it.title }}${if (profile.race == CharacterRace.HUMAN) " с ещё незнакомым герою NPC" else ""}.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CharacterEditor(original: CharacterProfile?, onDismiss: () -> Unit, onSave: (CharacterProfile) -> Unit, error: String?) {
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(original?.description ?: "") }
    var className by rememberSaveable { mutableStateOf((original?.characterClass ?: CharacterClass.WARRIOR).name) }
    var raceName by rememberSaveable { mutableStateOf((original?.race ?: CharacterRace.HUMAN).name) }
    var backgroundName by rememberSaveable { mutableStateOf((original?.background ?: CharacterBackground.SOLDIER).name) }
    var scores by rememberSaveable { mutableStateOf(original?.let { profile -> Ability.entries.map { profile.scores.getValue(it) } } ?: CharacterProfile.STANDARD_SCORES) }
    var chosenAvatar by rememberSaveable { mutableStateOf(original?.avatarId) }
    val avatarId = chosenAvatar ?: PreparedAvatars.id(CharacterRace.valueOf(raceName), CharacterClass.valueOf(className))
    val profile = CharacterProfile(name.trim(), CharacterClass.valueOf(className), CharacterRace.valueOf(raceName),
        CharacterBackground.valueOf(backgroundName), Ability.entries.zip(scores).toMap(), description.trim(), avatarId)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GameBackdrop {
            Column(Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = 820.dp).fillMaxWidth()
                .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                MenuHeader(if (original == null) "Новый герой" else "Настройка героя", "Облик, характер и способности", onDismiss)
                CharacterPortrait(profile, Modifier.size(156.dp).clip(MaterialTheme.shapes.large)
                    .border(1.dp, AdventureGold.copy(alpha = .6f), MaterialTheme.shapes.large).align(Alignment.CenterHorizontally))
                AdventureSectionTitle("Имя и характер")
                AdventurePanel(Modifier.fillMaxWidth()) {
                    CharacterTextField(name, { name = it.take(24) }, "Имя героя", singleLine = true)
                    CharacterTextField(description, { description = it.take(240) }, "Характер и история (необязательно)")
                }
                AdventureSectionTitle("Происхождение")
                AdventurePanel(Modifier.fillMaxWidth()) {
                    CharacterOption("Класс", className, CharacterClass.entries.map { it.name to it.title }) { className = it }
                    CharacterOption("Раса", raceName, CharacterRace.entries.map { it.name to it.title }) { raceName = it }
                    CharacterOption("Предыстория", backgroundName, CharacterBackground.entries.map { it.name to it.title }) { backgroundName = it }
                }
                AdventureSectionTitle("Характеристики")
                AdventurePanel(Modifier.fillMaxWidth()) {
                    Text("Распределите 15, 14, 13, 12, 10, 8. Выбор числа меняет его местами с прежним владельцем.",
                        color = AdventureMuted, style = MaterialTheme.typography.bodyMedium)
                    Ability.entries.forEachIndexed { index, ability ->
                        CharacterOption(ability.title, scores[index].toString(), CharacterProfile.STANDARD_SCORES.map { it.toString() to "$it (${signedBonus(kotlin.math.floor((it - 10) / 2.0).toInt())})" }) { value ->
                            val next = scores.toMutableList()
                            val other = next.indexOf(value.toInt())
                            next[other] = next[index]; next[index] = value.toInt(); scores = next
                        }
                    }
                }
                AdventureSectionTitle("Способности героя")
                AdventurePanel(Modifier.fillMaxWidth()) { CharacterSummary(profile) }
                AdventureSectionTitle("Облик героя")
                AdventurePanel(Modifier.fillMaxWidth()) {
                    Text("16 готовых портретов: все сочетания расы и класса. Можно выбрать любой облик; числа характеристик его не ограничивают.",
                        color = AdventureMuted, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CharacterRace.entries.forEach { race -> CharacterClass.entries.forEach { characterClass ->
                            val id = PreparedAvatars.id(race, characterClass)
                            val selectedAvatar = id == avatarId
                            Column(Modifier.width(96.dp).clip(MaterialTheme.shapes.small)
                                .background(if (selectedAvatar) AdventureGold.copy(alpha = .14f) else AdventureInk.copy(alpha = .6f))
                                .border(if (selectedAvatar) 2.dp else 1.dp,
                                    if (selectedAvatar) AdventureGold else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                                .selectable(selected = selectedAvatar, role = Role.RadioButton, onClick = { chosenAvatar = id }).padding(5.dp)) {
                                Image(painterResource(avatarResource(id)), "${race.title}, ${characterClass.title}",
                                    Modifier.size(86.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                                Text(race.title, style = MaterialTheme.typography.bodySmall)
                                Text(characterClass.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        } }
                    }
                    AdventureButton(secondary = true, onClick = { chosenAvatar = null }) { Text("Подобрать по расе и классу") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                AdventureButton(onClick = { onSave(profile) }, enabled = profile.validationError() == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Сохранить и выбрать") }
            }
        }
    }
}

@Composable
private fun CharacterTextField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = false) {
    AdventureTextField(value, onChange, label = label, singleLine = singleLine, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CharacterOption(title: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        AdventureButton(secondary = true, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = AdventureMuted)
                Text(options.first { it.first == value }.second, style = MaterialTheme.typography.titleMedium)
            }
            Text("⌄", color = AdventureGold, modifier = Modifier.padding(start = 12.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) },
                    trailingIcon = { if (key == value) Text("◆", color = AdventureGold) },
                    onClick = { onChange(key); expanded = false })
            }
        }
    }
}

internal fun signedBonus(value: Int): String = if (value >= 0) "+$value" else "−${-value}"
