package ru.markn.dnd.adventure

import kotlin.test.*

class DirectSpeechTest {
    @Test fun onlyExplicitWholeInputIsSpeech() {
        assertEquals("Привет!", directSpeechPresentation(" «Привет!» ")!!.single().text)
        assertEquals("Привет!", directSpeechPresentation("\"Привет!\"")!!.single().text)
        assertEquals("Он сказал «привет».", directSpeechPresentation("«Он сказал «привет».»")!!.single().text)
        for (input in listOf("Привет!", "Говорю привет и открываю дверь", "«Привет» и открываю дверь",
            "«Привет» открываю дверь «Спасибо»", "«»", "« «Привет»", "«${"я".repeat(500)}»"))
            assertNull(directSpeechPresentation(input), input)
    }
}
