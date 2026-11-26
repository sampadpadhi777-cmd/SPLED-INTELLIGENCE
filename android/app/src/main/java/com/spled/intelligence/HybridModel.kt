package com.spled.intelligence

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp

/** Dependency-free on-device inference for exported, shadow-approved research artifacts. */
class HybridModel(context: Context) {
    private val artifact: JSONObject? = try {
        context.assets.open("hybrid_model.json").bufferedReader().use { JSONObject(it.readText()) }
    } catch (_: Exception) { null }

    val enabled: Boolean = artifact?.let { a ->
        try {
            val n = a.getJSONArray("features").length()
            a.optBoolean("enabled_for_shadow", false) && a.optInt("schema_version", -1) == 1 &&
                a.optString("model_type") == "standardized_logistic_regression_platt" && n > 0 &&
                a.getJSONArray("imputer_medians").length() == n &&
                a.getJSONArray("scaler_means").length() == n &&
                a.getJSONArray("scaler_scales").length() == n &&
                a.getJSONArray("coefficients").length() == n
        } catch (_: Exception) { false }
    } ?: false

    fun probability(features: Map<String, Double>): Double? {
        val a = artifact ?: return null
        if (!enabled) return null
        return try {
            val names = a.getJSONArray("features")
            val medians = a.getJSONArray("imputer_medians")
            val means = a.getJSONArray("scaler_means")
            val scales = a.getJSONArray("scaler_scales")
            val coefs = a.getJSONArray("coefficients")
            if (names.length() == 0 || listOf(medians, means, scales, coefs).any { it.length() != names.length() }) return null
            var z = a.getDouble("intercept")
            for (i in 0 until names.length()) {
                val name = names.getString(i)
                val raw = features[name] ?: medians.getDouble(i)
                val scaled = (raw - means.getDouble(i)) /
                    scales.getDouble(i).takeIf { it != 0.0 }!!
                z += scaled * coefs.getDouble(i)
            }
            val rawP = 1.0 / (1.0 + exp(-z.coerceIn(-35.0, 35.0)))
            val logit = kotlin.math.ln(rawP.coerceIn(1e-6, 1.0-1e-6) / (1.0-rawP.coerceIn(1e-6, 1.0-1e-6)))
            val calibrated = a.getDouble("calibration_slope") * logit + a.getDouble("calibration_intercept")
            (1.0 / (1.0 + exp(-calibrated.coerceIn(-35.0, 35.0)))).coerceIn(0.0, 1.0)
        } catch (_: Exception) { null }
    }

    fun threshold(): Double = artifact?.optDouble("threshold", 0.60) ?: 0.60
    fun status(): String = when {
        artifact == null -> "MODEL_NOT_INSTALLED"
        enabled -> "SHADOW_MODEL_ENABLED"
        else -> "MODEL_NOT_VALIDATED"
    }
}
