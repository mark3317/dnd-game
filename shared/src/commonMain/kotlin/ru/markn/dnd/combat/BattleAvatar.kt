package ru.markn.dnd.combat

import androidx.compose.animation.core.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.compose.resources.imageResource
import ru.markn.dnd.generated.resources.*
import ru.markn.dnd.adventure.ui.LocalReducedMotion

internal enum class BattlePose { IDLE, ATTACK, HURT, DODGE, HELP, HEALED }

/** Actual row/column boundaries in the 1536x1024 source art, rather than a uniform grid. */
internal enum class BattleAtlas(val top: List<Int>, val bottom: List<Int>, val splitY: Int) {
    WARRIOR(listOf(0, 490, 1060, 1536), listOf(0, 485, 1045, 1536), 490),
    ROGUE(listOf(0, 520, 1050, 1536), listOf(0, 530, 1050, 1536), 500),
    MAGE(listOf(0, 430, 1110, 1536), listOf(0, 490, 1100, 1536), 480),
    BARD(listOf(0, 510, 1100, 1536), listOf(0, 490, 1050, 1536), 500),
    GUARD(listOf(0, 520, 1060, 1536), listOf(0, 520, 1060, 1536), 512),
    RAIDER(listOf(0, 520, 1050, 1536), listOf(0, 520, 1050, 1536), 510),
}

/** Web imageResource initially returns a transparent 1x1 bitmap while fetching the PNG. */
internal fun battleAvatarPainter(sheet: ImageBitmap, pose: BattlePose, atlas: BattleAtlas? = null): Painter {
    val frameWidth = sheet.width / 3
    val frameHeight = sheet.height / 2
    // A 0x0 intrinsic size makes ContentScale.Fit produce NaN and Painter.draw fail in inset().
    if (frameWidth == 0 || frameHeight == 0) return BitmapPainter(sheet)
    if (atlas != null) {
        val column = pose.ordinal % 3
        val upper = pose.ordinal < 3
        val boundaries = if (upper) atlas.top else atlas.bottom
        val left = boundaries[column] * sheet.width / 1536
        val right = boundaries[column + 1] * sheet.width / 1536
        val top = if (upper) 0 else atlas.splitY * sheet.height / 1024
        val bottom = if (upper) atlas.splitY * sheet.height / 1024 else sheet.height
        val frame = BitmapPainter(sheet, IntOffset(left, top), IntSize(right - left, bottom - top))
        // A common canvas keeps scale stable across poses, with room for weapons and effects.
        return object : Painter() {
            override val intrinsicSize = Size(700f, 560f)
            override fun DrawScope.onDraw() {
                val frameSize = Size((right - left) * 1536f / sheet.width, (bottom - top) * 1024f / sheet.height)
                withTransform({ scale(size.width / 700f, size.height / 560f, pivot = androidx.compose.ui.geometry.Offset.Zero) }) {
                    translate((700f - frameSize.width) / 2f, 550f - frameSize.height) {
                        with(frame) { draw(frameSize) }
                    }
                }
            }
        }
    }
    return BitmapPainter(sheet, IntOffset((pose.ordinal % 3) * frameWidth, (pose.ordinal / 3) * frameHeight),
        IntSize(frameWidth, frameHeight))
}

@Composable
internal fun BattleAvatar(fighter: DemoFighter, modifier: Modifier = Modifier,
    pose: BattlePose = BattlePose.IDLE, facingLeft: Boolean = false, animateIdle: Boolean = true) {
    val atlas = when {
        fighter.enemy -> if (fighter.range > 1) BattleAtlas.RAIDER else BattleAtlas.GUARD
        fighter.characterClass == DemoClass.ROGUE -> BattleAtlas.ROGUE
        fighter.characterClass == DemoClass.MAGE -> BattleAtlas.MAGE
        fighter.characterClass == DemoClass.BARD -> BattleAtlas.BARD
        else -> BattleAtlas.WARRIOR
    }
    val resource = when (atlas) {
        BattleAtlas.RAIDER -> Res.drawable.combat_raider_v1
        BattleAtlas.GUARD -> Res.drawable.combat_guard_v1
        BattleAtlas.ROGUE -> Res.drawable.combat_rogue_v1
        BattleAtlas.MAGE -> Res.drawable.combat_mage_v1
        BattleAtlas.BARD -> Res.drawable.combat_bard_v1
        BattleAtlas.WARRIOR -> Res.drawable.combat_warrior_v1
    }
    val sheet = imageResource(resource)
    val reduced = LocalReducedMotion.current
    val breathing = ru.markn.dnd.adventure.ui.pausedLoop(0f, 1f, 1050, animateIdle && !reduced)
    ru.markn.dnd.adventure.ui.PausableCrossfade(pose, modifier = modifier.graphicsLayer {
        scaleX = if (facingLeft) -1f else 1f
        scaleY = 1f + breathing * .018f
        translationY = -breathing * 2f
        if (fighter.hp == 0) { rotationZ = if (facingLeft) -22f else 22f; alpha = .65f }
    }, millis = if (reduced) 0 else 130) { currentPose ->
        val painter = remember(sheet, currentPose, atlas) { battleAvatarPainter(sheet, currentPose, atlas) }
        Image(painter, fighter.name + " · " + currentPose.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}
