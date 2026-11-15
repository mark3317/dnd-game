package ru.markn.dnd.adventure.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import ru.markn.dnd.generated.resources.*

internal val AdventureGold = Color(0xFFE8B975)
internal val AdventureMuted = Color(0xFF9BACB4)
internal val AdventureGreen = Color(0xFF9CC8AB)
internal val AdventureInk = Color(0xFF0D171E)
internal val AdventurePanelColor = Color(0xFF18262F)
internal val AdventureParchment = Color(0xFFF0E9DD)

@Composable
fun AdventureTheme(content: @Composable () -> Unit) {
    val display = FontFamily(Font(Res.font.cormorant_garamond))
    val body = FontFamily(Font(Res.font.nunito_sans))
    MaterialTheme(
        typography = Typography(
            displayLarge = TextStyle(fontFamily = display, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1).sp),
            displayMedium = TextStyle(fontFamily = display, fontSize = 46.sp, lineHeight = 50.sp),
            displaySmall = TextStyle(fontFamily = display, fontSize = 38.sp, lineHeight = 44.sp),
            headlineLarge = TextStyle(fontFamily = display, fontSize = 36.sp, lineHeight = 42.sp),
            headlineMedium = TextStyle(fontFamily = display, fontSize = 30.sp, lineHeight = 36.sp),
            headlineSmall = TextStyle(fontFamily = display, fontSize = 26.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontFamily = display, fontSize = 23.sp, lineHeight = 29.sp),
            titleMedium = TextStyle(fontFamily = body, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontFamily = body, fontSize = 17.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontFamily = body, fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .4.sp),
            labelMedium = TextStyle(fontFamily = body, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            labelSmall = TextStyle(fontFamily = body, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 2.sp),
        ),
        shapes = Shapes(
            extraSmall = CutCornerShape(2.dp),
            small = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
            medium = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
            large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
            extraLarge = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
        ),
        colorScheme = darkColorScheme(
            primary = AdventureGold,
            onPrimary = Color(0xFF302312),
            primaryContainer = Color(0xFF49371F),
            onPrimaryContainer = Color(0xFFF6DDB5),
            inversePrimary = Color(0xFF795624),
            secondary = AdventureGreen,
            onSecondary = Color(0xFF102A20),
            secondaryContainer = Color(0xFF243D34),
            onSecondaryContainer = Color(0xFFC8E9D2),
            tertiary = Color(0xFF95BBD1),
            onTertiary = Color(0xFF102C3D),
            tertiaryContainer = Color(0xFF233D4D),
            onTertiaryContainer = Color(0xFFD0E8F5),
            background = AdventureInk,
            onBackground = AdventureParchment,
            surface = AdventurePanelColor,
            onSurface = AdventureParchment,
            surfaceDim = AdventureInk,
            surfaceBright = Color(0xFF30424D),
            surfaceContainerLowest = Color(0xFF091218),
            surfaceContainerLow = Color(0xFF14212A),
            surfaceContainer = AdventurePanelColor,
            surfaceContainerHigh = Color(0xFF20313B),
            surfaceContainerHighest = Color(0xFF293D48),
            surfaceTint = Color.Transparent,
            inverseSurface = AdventureParchment,
            inverseOnSurface = AdventureInk,
            surfaceVariant = Color(0xFF24343E),
            onSurfaceVariant = Color(0xFFB7C3C8),
            outline = Color(0xFF40505A),
            outlineVariant = Color(0xFF39434A),
            error = Color(0xFFF2A79C),
            onError = Color(0xFF3D1411),
            errorContainer = Color(0xFF4A2524),
            onErrorContainer = Color(0xFFFFDAD3),
            scrim = Color(0xFF030A0F),
        ),
        content = content,
    )
}
