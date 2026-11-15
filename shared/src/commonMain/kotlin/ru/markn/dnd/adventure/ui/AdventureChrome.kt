package ru.markn.dnd.adventure.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared editable surface; retain platform text/IME/accessibility behavior. */
@Composable
internal fun AdventureTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: String? = null, placeholder: String? = null, singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 4, enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val labelContent: (@Composable () -> Unit)? = label?.let { text -> { Text(text) } }
    val placeholderContent: (@Composable () -> Unit)? = placeholder?.let { text -> { Text(text) } }
    OutlinedTextField(value, onValueChange, modifier = modifier, label = labelContent, placeholder = placeholderContent,
        enabled = enabled, singleLine = singleLine, maxLines = maxLines, shape = MaterialTheme.shapes.small,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AdventureInk.copy(alpha = .75f),
            unfocusedContainerColor = AdventureInk.copy(alpha = .5f),
            focusedBorderColor = AdventureGold,
        ))
}

/** Shared frame for menus and utility panels. The scene itself stays unobstructed. */
@Composable
internal fun AdventurePanel(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = AdventurePanelColor.copy(alpha = .94f),
        border = BorderStroke(1.dp, if (emphasized) AdventureGold.copy(alpha = .65f) else AdventureGold.copy(alpha = .22f)),
    ) {
        Column(
            Modifier.background(Brush.verticalGradient(listOf(AdventureGold.copy(alpha = .045f), AdventureInk.copy(alpha = .28f))))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Angular, framed action with keyboard and pointer feedback, without a pill silhouette. */
@Composable
internal fun AdventureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val emphasis by animateFloatAsState(
        if (enabled && (hovered || focused || pressed)) 1f else 0f,
        tween(GameMotionProfile(LocalReducedMotion.current).transitionMillis(140)), label = "framed action",
    )
    val background = when {
        !enabled -> AdventurePanelColor.copy(alpha = .7f)
        secondary -> AdventurePanelColor
        else -> AdventureGold.copy(alpha = .15f + emphasis * .12f)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.heightIn(min = 48.dp).semantics { role = Role.Button },
        shape = MaterialTheme.shapes.small,
        color = background,
        contentColor = if (enabled) AdventureParchment else AdventureMuted.copy(alpha = .55f),
        border = BorderStroke(
            if (focused && enabled) 2.dp else 1.dp,
            AdventureGold.copy(alpha = if (!enabled) .12f else if (secondary) .3f + emphasis * .6f else .65f + emphasis * .3f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
internal fun AdventureSectionTitle(title: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AdventureDiamond()
        Text(title, style = MaterialTheme.typography.titleLarge)
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(AdventureGold.copy(alpha = .3f), AdventureGold.copy(alpha = 0f)))))
    }
}

@Composable
internal fun AdventureDivider(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(AdventureGold.copy(alpha = 0f), AdventureGold.copy(alpha = .35f)))))
        AdventureDiamond()
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(AdventureGold.copy(alpha = .35f), AdventureGold.copy(alpha = 0f)))))
    }
}

@Composable
private fun AdventureDiamond() {
    Canvas(Modifier.size(7.dp)) {
        drawPath(Path().apply {
            moveTo(size.width / 2, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(size.width / 2, size.height)
            lineTo(0f, size.height / 2)
            close()
        }, AdventureGold.copy(alpha = .7f))
    }
}

/** Decoration only: the owning toggleable row supplies the switch semantics and input. */
@Composable
internal fun AdventureToggleMark(checked: Boolean) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier.size(width = 44.dp, height = 24.dp)
            .background(if (checked) AdventureGold.copy(alpha = .18f) else AdventureInk, shape)
            .border(1.dp, if (checked) AdventureGold else AdventureMuted.copy(alpha = .5f), shape)
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(14.dp).background(if (checked) AdventureGold else AdventureMuted, shape))
    }
}
