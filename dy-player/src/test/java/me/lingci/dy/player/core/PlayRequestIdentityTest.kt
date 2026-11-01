package me.lingci.dy.player.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRequestIdentityTest {

    @Test
    fun outOfOrderCallbacks_onlyLatestRequestIsAccepted() {
        val gate = PlayRequestGate()
        val a = gate.begin("a", submittedAt = 1L)
        val b = gate.begin("b", submittedAt = 2L)
        val c = gate.begin("c", submittedAt = 3L)

        assertFalse(gate.isCurrent(a))
        assertFalse(gate.isCurrent(b))
        assertTrue(gate.isCurrent(c))
    }

    @Test
    fun retryingSameMedia_rejectsPreviousAttempt() {
        val gate = PlayRequestGate()
        val first = gate.begin("same", submittedAt = 1L)
        val retry = gate.begin("same", submittedAt = 2L)

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(retry))
    }

    @Test
    fun returningToPreviousMedia_doesNotReviveItsOldRequest() {
        val gate = PlayRequestGate()
        val oldA = gate.begin("a")
        gate.begin("b")
        val newA = gate.begin("a")

        assertFalse(gate.isCurrent(oldA))
        assertTrue(gate.isCurrent(newA))
    }

    @Test
    fun invalidate_rejectsInflightCallback() {
        val gate = PlayRequestGate()
        val request = gate.begin("a")

        gate.invalidate()

        assertFalse(gate.isCurrent(request))
    }
}
