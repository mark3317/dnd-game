package ru.markn.dnd.combat

import java.net.URI
import javax.imageio.ImageIO
import kotlin.test.*
import ru.markn.dnd.generated.resources.Res

class CombatAssetsTest {
    @Test fun everyCombatAtlasHasSixReadableFramesAndAlpha() {
        for (name in listOf("warrior", "rogue", "mage", "bard", "guard", "raider")) {
            val image = URI(Res.getUri("drawable/combat_${name}_v1.png")).toURL().openStream().use(ImageIO::read)
            assertEquals(1536, image.width, name)
            assertEquals(1024, image.height, name)
            assertTrue(image.colorModel.hasAlpha(), name)
            assertTrue((image.getRGB(0, 0) ushr 24) <= 8, "$name: transparent corner")
            repeat(6) { frame ->
                val left = frame % 3 * 512
                val top = frame / 3 * 512
                val opaque = (0 until 512 step 8).sumOf { y -> (0 until 512 step 8).count { x ->
                    (image.getRGB(left + x, top + y) ushr 24) > 200 } }
                assertTrue(opaque > 100, "$name frame $frame must contain a visible character")
            }
        }
    }

    @Test fun environmentBackgroundsAreBundledAndMapMatchesGridAspect() {
        val map = URI(Res.getUri("drawable/combat_field_v1.png")).toURL().openStream().use(ImageIO::read)
        assertEquals(6.0 / 5.0, map.width.toDouble() / map.height, .001)
        val stage = URI(Res.getUri("drawable/combat_stage_v1.png")).toURL().openStream().use(ImageIO::read)
        assertTrue(stage.width > stage.height)
    }
}
