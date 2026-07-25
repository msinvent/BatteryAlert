package com.batteryalert.app

/**
 * User-configurable alert thresholds and siren lengths. Pure and
 * framework-free so the validation rules are unit-testable; persistence
 * lives in BatteryCheck.
 */
data class ThresholdConfig(
    val high: Int,
    val mid: Int,
    val low: Int,
    val highSirenSec: Int = DEFAULT_HIGH_SIREN_SEC,
    val midSirenSec: Int = DEFAULT_URGENT_SIREN_SEC,
    val lowSirenSec: Int = DEFAULT_URGENT_SIREN_SEC
) {

    // Descending order is implied: each gap must be >= MIN_GAP.
    fun isValid(): Boolean =
        low >= MIN_PERCENT &&
        high <= MAX_PERCENT &&
        high - mid >= MIN_GAP &&
        mid - low >= MIN_GAP &&
        highSirenSec in SIREN_CHOICES_SEC &&
        midSirenSec in SIREN_CHOICES_SEC &&
        lowSirenSec in SIREN_CHOICES_SEC

    companion object {
        const val MIN_GAP = 5
        const val MIN_PERCENT = 5
        const val MAX_PERCENT = 95
        val SIREN_CHOICES_SEC = listOf(15, 30, 45, 60)
        private const val DEFAULT_HIGH_SIREN_SEC = 30
        private const val DEFAULT_URGENT_SIREN_SEC = 60
        val DEFAULT = ThresholdConfig(high = 20, mid = 15, low = 10)

        fun nextSirenChoice(currentSec: Int): Int {
            val idx = SIREN_CHOICES_SEC.indexOf(currentSec)
            return SIREN_CHOICES_SEC[(idx + 1) % SIREN_CHOICES_SEC.size]
        }
    }
}
