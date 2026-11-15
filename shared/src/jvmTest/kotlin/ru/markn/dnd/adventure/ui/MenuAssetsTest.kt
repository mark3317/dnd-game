package ru.markn.dnd.adventure.ui

import java.net.URI
import java.awt.Font
import javax.imageio.ImageIO
import kotlin.test.*
import ru.markn.dnd.adventure.PreparedAvatars
import ru.markn.dnd.generated.resources.Res

class MenuAssetsTest {
    @Test fun everySceneAvatarHasTransparentMarginsAndVisibleContent() {
        val ids = PreparedAvatars.ids.map { "avatar_$it" } +
            listOf("hero_rin", "hero_mira", "hero_bran", "archive_archivist", "archive_courier")
        for (id in ids) {
            val sprite = URI(Res.getUri("drawable/stage_$id.png")).toURL().openStream().use(ImageIO::read)
            assertEquals(640, sprite.width, id)
            assertEquals(1024, sprite.height, id)
            assertTrue(sprite.colorModel.hasAlpha(), id)
            var transparent = 0
            var opaque = 0
            for (y in 0 until sprite.height step 8) for (x in 0 until sprite.width step 8) {
                val alpha = sprite.getRGB(x, y).ushr(24)
                if (alpha == 0) transparent++
                if (alpha >= 240) opaque++
            }
            assertTrue(transparent > 1000, "$id needs transparent space around its silhouette")
            assertTrue(opaque > 500, "$id must contain a visible character")
            for (x in 0 until sprite.width) {
                assertEquals(0, sprite.getRGB(x, 0).ushr(24), "$id top margin")
                assertEquals(0, sprite.getRGB(x, sprite.height - 1).ushr(24), "$id bottom margin")
            }
            for (y in 0 until sprite.height) {
                assertEquals(0, sprite.getRGB(0, y).ushr(24), "$id left margin")
                assertEquals(0, sprite.getRGB(sprite.width - 1, y).ushr(24), "$id right margin")
            }
        }
    }

    @Test fun everySelectablePortraitAndMenuBackgroundIsBundledAndReadable() {
        for (id in PreparedAvatars.ids) {
            val portrait = URI(Res.getUri("drawable/avatar_$id.png")).toURL().openStream().use(ImageIO::read)
            assertTrue(portrait.width >= 256, id)
            assertEquals(portrait.width, portrait.height, id)
        }
        val background = URI(Res.getUri("drawable/menu_campfire.png")).toURL().openStream().use(ImageIO::read)
        assertTrue(background.width > background.height)
    }

    @Test fun bothBundledFontsCoverCyrillicAndIncludeTheirLicenses() {
        for (name in listOf("cormorant_garamond", "nunito_sans")) {
            val font = URI(Res.getUri("font/$name.ttf")).toURL().openStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }
            assertEquals(-1, font.canDisplayUpTo("Истории у костра Ёё — 0123456789"), name)
        }
        for (name in listOf("cormorant", "nunito")) {
            val license = URI(Res.getUri("files/licenses/${name}_ofl.txt")).toURL().readText()
            assertTrue(license.contains("SIL OPEN FONT LICENSE"))
        }
    }
}
