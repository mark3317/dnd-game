package ru.markn.dnd.adventure

/** Recorded by the authority; presentation never rolls dice or applies game effects. */
@kotlinx.serialization.Serializable
data class DiceEvent(
    val title: String,
    val actor: String,
    val dice: List<Int>,
    val sides: Int,
    val bonus: Int = 0,
    val difficulty: Int? = null,
    val success: Boolean? = null,
    val outcome: String = "Результат броска",
    val best: Boolean = false,
    val detail: String = "",
    val logIndex: Int = -1,
) {
    val total get() = (if (best) dice.max() else dice.sum()) + bonus
}
