package ru.markn.dnd.adventure.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import ru.markn.dnd.adventure.*
import ru.markn.dnd.generated.resources.*

internal fun standingHeight(profile: CharacterProfile): Float = when (
    profile.avatarId?.substringBefore('_') ?: profile.race.name.lowercase()
) {
    "dwarf" -> .78f
    "halfling" -> .64f
    else -> 1f
}

internal fun avatarResource(id: String): DrawableResource = when (id) {
    "human_warrior" -> Res.drawable.avatar_human_warrior
    "human_rogue" -> Res.drawable.avatar_human_rogue
    "human_mage" -> Res.drawable.avatar_human_mage
    "human_bard" -> Res.drawable.avatar_human_bard
    "elf_warrior" -> Res.drawable.avatar_elf_warrior
    "elf_rogue" -> Res.drawable.avatar_elf_rogue
    "elf_mage" -> Res.drawable.avatar_elf_mage
    "elf_bard" -> Res.drawable.avatar_elf_bard
    "dwarf_warrior" -> Res.drawable.avatar_dwarf_warrior
    "dwarf_rogue" -> Res.drawable.avatar_dwarf_rogue
    "dwarf_mage" -> Res.drawable.avatar_dwarf_mage
    "dwarf_bard" -> Res.drawable.avatar_dwarf_bard
    "halfling_warrior" -> Res.drawable.avatar_halfling_warrior
    "halfling_rogue" -> Res.drawable.avatar_halfling_rogue
    "halfling_mage" -> Res.drawable.avatar_halfling_mage
    else -> Res.drawable.avatar_halfling_bard
}

internal fun portraitResource(profile: CharacterProfile): DrawableResource =
    profile.avatarId?.let(::avatarResource) ?: when (profile) {
        ReadyCharacters.bran -> Res.drawable.hero_bran
        ReadyCharacters.mira -> Res.drawable.hero_mira
        ReadyCharacters.rin -> Res.drawable.hero_rin
        else -> avatarResource(PreparedAvatars.id(profile.race, profile.characterClass))
    }

// Scene sprites and card crops share one generated master, but have different framing.
internal fun standingResource(profile: CharacterProfile): DrawableResource =
    profile.avatarId?.let(::standingAvatarResource) ?: when (profile) {
        ReadyCharacters.bran -> Res.drawable.stage_hero_bran
        ReadyCharacters.mira -> Res.drawable.stage_hero_mira
        ReadyCharacters.rin -> Res.drawable.stage_hero_rin
        else -> standingAvatarResource(PreparedAvatars.id(profile.race, profile.characterClass))
    }

internal fun standingAvatarResource(id: String): DrawableResource = when (id) {
    "human_warrior" -> Res.drawable.stage_avatar_human_warrior
    "human_rogue" -> Res.drawable.stage_avatar_human_rogue
    "human_mage" -> Res.drawable.stage_avatar_human_mage
    "human_bard" -> Res.drawable.stage_avatar_human_bard
    "elf_warrior" -> Res.drawable.stage_avatar_elf_warrior
    "elf_rogue" -> Res.drawable.stage_avatar_elf_rogue
    "elf_mage" -> Res.drawable.stage_avatar_elf_mage
    "elf_bard" -> Res.drawable.stage_avatar_elf_bard
    "dwarf_warrior" -> Res.drawable.stage_avatar_dwarf_warrior
    "dwarf_rogue" -> Res.drawable.stage_avatar_dwarf_rogue
    "dwarf_mage" -> Res.drawable.stage_avatar_dwarf_mage
    "dwarf_bard" -> Res.drawable.stage_avatar_dwarf_bard
    "halfling_warrior" -> Res.drawable.stage_avatar_halfling_warrior
    "halfling_rogue" -> Res.drawable.stage_avatar_halfling_rogue
    "halfling_mage" -> Res.drawable.stage_avatar_halfling_mage
    else -> Res.drawable.stage_avatar_halfling_bard
}

@Composable
fun CharacterPortrait(profile: CharacterProfile, modifier: Modifier = Modifier) {
    Image(painterResource(portraitResource(profile)), "Аватар ${profile.name}", modifier, contentScale = ContentScale.Crop)
}
