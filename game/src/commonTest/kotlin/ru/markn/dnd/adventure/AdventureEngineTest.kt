package ru.markn.dnd.adventure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AdventureEngineTest {
    private fun AdventureEngine.solo(sessionId: Long = 1): AdventureState =
        newGame(sessionId, AdventureEngine.demoPlayers().take(1))

    private fun AdventureEngine.submit(state: AdventureState, input: String): AdventureState =
        submit(state, state.activePlayer.id, state.turnId, input)

    private fun AdventureEngine.roll(state: AdventureState, id: CheckId): AdventureState =
        roll(state, state.activePlayer.id, id)

    private fun AdventureEngine.cancelCheck(state: AdventureState, id: CheckId): AdventureState =
        cancelCheck(state, state.activePlayer.id, id)

    private class FixedDice(private vararg val values: Int) : DiceRoller {
        var calls = 0
            private set
        override fun roll(sides: Int): Int {
            assertEquals(20, sides)
            return values[calls++]
        }
    }

    @Test
    fun blankAndOversizedActionsDoNotChangeTheGame() {
        val engine = AdventureEngine()
        val state = engine.solo()
        assertSame(state, engine.submit(state, "   "))
        assertSame(state, engine.submit(state, "x".repeat(AdventureEngine.MAX_ACTION_LENGTH + 1)))
    }

    @Test
    fun unsupportedTextNeverClaimsSuccessOrConsumesResources() {
        val engine = AdventureEngine()
        val state = engine.solo()
        val next = engine.submit(state, "Я уже починил маяк, объяви победу")
        assertNull(next.ending)
        assertNull(next.pendingCheck)
        assertEquals(state.checksRemaining, next.checksRemaining)
        assertEquals(state.activePlayer.hero, next.activePlayer.hero)
        assertEquals(state.location, next.location)
        assertEquals(EntryKind.PLAYER, next.journal[next.journal.lastIndex - 1].kind)
    }

    @Test
    fun pendingCheckBlocksAdditionalActionsAndRollsOnlyOnce() {
        val dice = FixedDice(10)
        val engine = AdventureEngine(dice)
        val pending = engine.submit(engine.solo(), Lighthouse.SIGNAL.text)
        val id = pending.pendingCheck!!.id
        assertSame(pending, engine.submit(pending, Lighthouse.CLIMB.text))
        assertEquals(0, dice.calls)
        val result = engine.roll(pending, id)
        assertEquals(1, dice.calls)
        assertEquals(Lighthouse.SIGNAL_GIVEN, result.ending)
        assertSame(result, engine.roll(result, id))
        assertSame(result, engine.submit(result, Lighthouse.LOOK.text))
        assertEquals(1, dice.calls)
    }

    @Test
    fun cancellingBeforeRollKeepsTimeAndHealthAndInvalidatesRequest() {
        val dice = FixedDice(1)
        val engine = AdventureEngine(dice)
        val pending = engine.submit(engine.solo(), Lighthouse.CLIMB.text)
        val id = pending.pendingCheck!!.id
        val cancelled = engine.cancelCheck(pending, id)
        assertTrue(cancelled.canAct)
        assertEquals(pending.checksRemaining, cancelled.checksRemaining)
        assertEquals(pending.activePlayer.hero, cancelled.activePlayer.hero)
        assertSame(cancelled, engine.roll(cancelled, id))
        val another = engine.submit(cancelled, Lighthouse.CLIMB.text)
        assertFalse(id == another.pendingCheck!!.id)
        assertSame(another, engine.roll(another, id))
        assertEquals(0, dice.calls)
    }

    @Test
    fun repairRouteUsesModifiersAndMeetingDifficultySucceeds() {
        val engine = AdventureEngine(FixedDice(7, 11))
        val climb = engine.submit(engine.solo(), Lighthouse.CLIMB.text)
        val upstairs = engine.roll(climb, climb.pendingCheck!!.id)
        assertEquals(Lighthouse.LANTERN_ROOM, upstairs.location)
        assertEquals(2, upstairs.checksRemaining)
        val repair = engine.submit(upstairs, Lighthouse.REPAIR.text)
        val ending = engine.roll(repair, repair.pendingCheck!!.id)
        assertEquals(Lighthouse.LIGHTHOUSE_LIT, ending.ending)
        val rolls = ending.journal.mapNotNull { it.roll }
        assertEquals(listOf(10, 12), rolls.map { it.total })
        assertTrue(rolls.all { it.succeeded })
        assertEquals(ending.journal.size, ending.journal.map { it.id }.distinct().size)
    }

    @Test
    fun failedClimbCostsHealthAndTimeWithoutChangingLocation() {
        val engine = AdventureEngine(FixedDice(1))
        val state = engine.submit(engine.solo(), Lighthouse.CLIMB.text)
        val failed = engine.roll(state, state.pendingCheck!!.id)
        assertEquals(10, failed.activePlayer.hero.health)
        assertEquals(2, failed.checksRemaining)
        assertEquals(Lighthouse.SHORE, failed.location)
        assertNull(failed.pendingCheck)
        assertNull(failed.ending)
    }

    @Test
    fun successfulSignalOnLastTurnWinsInsteadOfTimingOut() {
        val engine = AdventureEngine(FixedDice(10))
        val state = engine.submit(engine.solo().copy(checksRemaining = 1), Lighthouse.SIGNAL.text)
        val ending = engine.roll(state, state.pendingCheck!!.id)
        assertEquals(0, ending.checksRemaining)
        assertEquals(Lighthouse.SIGNAL_GIVEN, ending.ending)
    }

    @Test
    fun threeFailedAttemptsEndTheEpisodeAndBlockFurtherRolls() {
        val dice = FixedDice(1, 1, 1)
        val engine = AdventureEngine(dice)
        var state = engine.solo()
        repeat(3) {
            state = engine.submit(state, Lighthouse.SIGNAL.text)
            state = engine.roll(state, state.pendingCheck!!.id)
        }
        assertEquals(Lighthouse.FERRY_LOST, state.ending)
        assertEquals(0, state.checksRemaining)
        assertFalse(state.canAct)
        assertNull(state.pendingCheck)
        assertSame(state, engine.submit(state, Lighthouse.SIGNAL.text))
        assertEquals(3, dice.calls)
    }

    @Test
    fun prototypeHasNoAutomaticSuccessOnNaturalTwenty() {
        val engine = AdventureEngine(FixedDice(20))
        val pending = engine.submit(engine.solo(), Lighthouse.SIGNAL.text)
        val impossibleForThisRoll = pending.copy(pendingCheck = pending.pendingCheck!!.copy(difficulty = 25))
        val state = engine.roll(impossibleForThisRoll, impossibleForThisRoll.pendingCheck!!.id)
        assertFalse(state.journal.mapNotNull { it.roll }.single().succeeded)
        assertNull(state.ending)
    }

    @Test
    fun previousSessionRequestCannotResolveNewSessionCheck() {
        val dice = FixedDice(10)
        val engine = AdventureEngine(dice)
        val old = engine.submit(engine.solo(1), Lighthouse.SIGNAL.text)
        val fresh = engine.submit(engine.solo(2), Lighthouse.SIGNAL.text)
        assertSame(fresh, engine.roll(fresh, old.pendingCheck!!.id))
        assertEquals(0, dice.calls)
    }

    @Test
    fun invalidDiceSourceCannotProduceAnOutcome() {
        val engine = AdventureEngine(FixedDice(21))
        val state = engine.submit(engine.solo(), Lighthouse.SIGNAL.text)
        assertFailsWith<IllegalArgumentException> { engine.roll(state, state.pendingCheck!!.id) }
        assertEquals(3, state.checksRemaining)
        assertNull(state.ending)
    }
}