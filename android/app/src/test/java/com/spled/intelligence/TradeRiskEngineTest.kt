package com.spled.intelligence

import org.junit.Assert.*
import org.junit.Test

class TradeRiskEngineTest {
    private val engine = TradeRiskEngine()

    @Test fun acceptsTradePlanOnlyWhenNetRiskChecksPass() {
        val result = engine.assess(100.0, 99.0, 102.0, 5.0, 2_000_000.0, 1_000L)
        assertTrue(result.allowed)
        assertTrue(result.estimatedNetRiskReward > 1.2)
    }

    @Test fun rejectsStaleMarketData() {
        val result = engine.assess(100.0, 99.0, 102.0, 5.0, 2_000_000.0, 15_000L)
        assertFalse(result.allowed)
        assertEquals("Market data stale", result.rejectionReason)
    }

    @Test fun rejectsWideSpreadAndLowLiquidity() {
        assertFalse(engine.assess(100.0,99.0,102.0,20.0,2_000_000.0,1_000L).allowed)
        assertFalse(engine.assess(100.0,99.0,102.0,5.0,500_000.0,1_000L).allowed)
    }
}
