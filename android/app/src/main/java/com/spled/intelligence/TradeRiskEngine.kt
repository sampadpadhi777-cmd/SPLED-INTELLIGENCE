package com.spled.intelligence

import kotlin.math.abs

data class RiskAssessment(
    val allowed: Boolean,
    val grossRiskReward: Double,
    val estimatedNetRiskReward: Double,
    val estimatedRoundTripCostPct: Double,
    val rejectionReason: String? = null
)

/** Independent deterministic gate. It never submits or sizes an exchange order. */
class TradeRiskEngine(
    private val feePerSidePct: Double = 0.0005,
    private val roundTripSlippagePct: Double = 0.0010,
    private val maxStopDistancePct: Double = 5.0,
    private val maxSpreadBps: Double = 15.0,
    private val minQuoteVolumeUsdt: Double = 1_000_000.0,
    private val maxDataAgeMs: Long = 10_000L,
    private val minGrossRr: Double = 1.5,
    private val minNetRr: Double = 1.2
) {
    fun assess(
        entry: Double, stop: Double, target: Double,
        spreadBps: Double, quoteVolumeUsdt: Double, dataAgeMs: Long
    ): RiskAssessment {
        val risk = abs(entry-stop)
        val reward = abs(target-entry)
        if (entry<=0.0 || stop<=0.0 || target<=0.0 || risk<=0.0 || reward<=0.0)
            return RiskAssessment(false,0.0,0.0,0.0,"Invalid entry/stop/target")
        if (dataAgeMs>maxDataAgeMs) return RiskAssessment(false,0.0,0.0,0.0,"Market data stale")
        if (!spreadBps.isFinite() || spreadBps>maxSpreadBps) return RiskAssessment(false,0.0,0.0,0.0,"Spread too wide or unavailable")
        if (quoteVolumeUsdt<minQuoteVolumeUsdt) return RiskAssessment(false,0.0,0.0,0.0,"Insufficient 24h quote volume")
        val riskPct=risk/entry*100.0
        if (riskPct>maxStopDistancePct) return RiskAssessment(false,0.0,0.0,0.0,"Structural stop distance exceeds 5%")
        val grossRr=reward/risk
        val costPct=2*feePerSidePct+roundTripSlippagePct
        val rewardNet=reward/entry-costPct
        val riskNet=risk/entry+costPct
        val netRr=if (riskNet>0) rewardNet/riskNet else -1.0
        if (grossRr<minGrossRr) return RiskAssessment(false,grossRr,netRr,costPct,"Gross R:R below 1.5")
        if (rewardNet<=0.0 || netRr<minNetRr) return RiskAssessment(false,grossRr,netRr,costPct,"Net reward/risk below 1.2 after assumed costs")
        return RiskAssessment(true,grossRr,netRr,costPct)
    }
}
