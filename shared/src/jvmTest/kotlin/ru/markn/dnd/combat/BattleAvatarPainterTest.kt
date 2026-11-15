package ru.markn.dnd.combat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.*

class BattleAvatarPainterTest {
    private fun drawAllPoses(sheet: ImageBitmap, expectedSize: Size, atlas: BattleAtlas? = null) {
        for (pose in BattlePose.entries) {
            val painter = battleAvatarPainter(sheet, pose, atlas)
            assertEquals(expectedSize, painter.intrinsicSize)
            val destination = Size(64f, 64f)
            val scale = ContentScale.Fit.computeScaleFactor(painter.intrinsicSize, destination)
            assertTrue(scale.scaleX.isFinite() && scale.scaleY.isFinite(), pose.name)
            val scaledSize = Size(painter.intrinsicSize.width * scale.scaleX, painter.intrinsicSize.height * scale.scaleY)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(ImageBitmap(64, 64)), destination) {
                with(painter) { draw(scaledSize) }
            }
        }
    }

    @Test fun webLoadingPlaceholderHasNonzeroIntrinsicSizeAndActuallyDraws() {
        drawAllPoses(ImageBitmap(1, 1), Size(1f, 1f))
    }

    @Test fun loadingThenLoadedAtlasRecomputesValidFramesForEveryPose() {
        drawAllPoses(ImageBitmap(1, 1), Size(1f, 1f))
        drawAllPoses(ImageBitmap(1536, 1024), Size(512f, 512f))
    }

    @Test fun undersizedAtlasAlsoDrawsWithoutDividingIntoEmptyFrames() {
        drawAllPoses(ImageBitmap(2, 1), Size(2f, 1f))
        drawAllPoses(ImageBitmap(3, 2), Size(1f, 1f))
    }

    @Test fun everyAtlasUsesStableCanvasAndDrawsEveryPoseAfterLoading() {
        for (atlas in BattleAtlas.entries) {
            drawAllPoses(ImageBitmap(1, 1), Size(1f, 1f), atlas)
            drawAllPoses(ImageBitmap(1536, 1024), Size(700f, 560f), atlas)
        }
    }

    @Test fun mageAttackIncludesBothSidesOutsideTheOldUniformCell() {
        val sheet = ImageBitmap(1536, 1024)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(sheet), Size(1536f, 1024f)) {
            drawRect(Color.Red, Offset(450f, 100f), Size(4f, 4f))
            drawRect(Color.Green, Offset(1080f, 100f), Size(4f, 4f))
        }
        val result = ImageBitmap(700, 560)
        val painter = battleAvatarPainter(sheet, BattlePose.ATTACK, BattleAtlas.MAGE)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(result), Size(700f, 560f)) {
            with(painter) { draw(size) }
        }
        val pixels = result.toPixelMap()
        assertEquals(Color.Red, pixels[32, 172])
        assertEquals(Color.Green, pixels[662, 172])
    }
}
