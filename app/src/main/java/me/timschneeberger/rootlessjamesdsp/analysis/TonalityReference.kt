package me.timschneeberger.rootlessjamesdsp.analysis

import kotlin.math.ln
import kotlin.math.pow

enum class TonalityReferenceKind {
    VerifiedMNoiseWav,
    ImportedReferenceWav,
    PinkNoiseDebug,
    MusicCorpusAverage,
    ApproximateFallback
}

data class TonalityAnalysisConfig(
    val sampleRate: Int,
    val fftSize: Int = 8192,
    val hopSize: Int = 2048,
    val bandsPerOctave: Int = 12,
    val minHz: Float = 20f,
    val maxHz: Float = minOf(20_000f, sampleRate / 2f)
)

data class TonalityReference(
    val referenceName: String,
    val referenceKind: TonalityReferenceKind,
    val sourceSha256: String?,
    val sourceMd5: String?,
    val analysisVersion: Int,
    val sampleRate: Int,
    val fftSize: Int,
    val hopSize: Int,
    val window: String,
    val bandsPerOctave: Int,
    val minHz: Float,
    val maxHz: Float,
    val frequencyHz: FloatArray,
    val referenceDb: FloatArray
) {
    val isVerified: Boolean
        get() = referenceKind == TonalityReferenceKind.VerifiedMNoiseWav

    val displayLabel: String
        get() = when (referenceKind) {
            TonalityReferenceKind.VerifiedMNoiseWav -> "verified M-Noise WAV"
            TonalityReferenceKind.ImportedReferenceWav -> "imported reference WAV"
            TonalityReferenceKind.PinkNoiseDebug -> "pink noise debug reference"
            TonalityReferenceKind.MusicCorpusAverage -> "music corpus average"
            TonalityReferenceKind.ApproximateFallback -> "approximate M-Noise-like curve"
        }
}

data class TonalityBandLayout(
    val centerHz: FloatArray,
    val lowerHz: FloatArray,
    val upperHz: FloatArray,
    val binStart: IntArray,
    val binEndExclusive: IntArray
) {
    companion object {
        fun create(config: TonalityAnalysisConfig): TonalityBandLayout {
            val centers = mutableListOf<Float>()
            var center = config.minHz
            val step = 2.0.pow(1.0 / config.bandsPerOctave).toFloat()
            while (center <= config.maxHz * 1.0001f) {
                centers += center
                center *= step
            }

            val halfStep = 2.0.pow(0.5 / config.bandsPerOctave).toFloat()
            val centerArray = centers.toFloatArray()
            val lower = FloatArray(centerArray.size) { i -> centerArray[i] / halfStep }
            val upper = FloatArray(centerArray.size) { i -> centerArray[i] * halfStep }
            val binStart = IntArray(centerArray.size)
            val binEnd = IntArray(centerArray.size)
            val hzPerBin = config.sampleRate.toFloat() / config.fftSize
            val nyquistBin = config.fftSize / 2

            for (i in centerArray.indices) {
                binStart[i] = (lower[i] / hzPerBin).toInt().coerceIn(1, nyquistBin)
                binEnd[i] = ((upper[i] / hzPerBin).toInt() + 1).coerceIn(binStart[i] + 1, nyquistBin + 1)
            }

            return TonalityBandLayout(centerArray, lower, upper, binStart, binEnd)
        }
    }
}

object TonalityReferenceFactory {
    const val ANALYSIS_VERSION = 1
    const val WINDOW_HANN = "hann"

    fun fallback(sampleRate: Int): TonalityReference {
        val config = TonalityAnalysisConfig(sampleRate = sampleRate)
        val layout = TonalityBandLayout.create(config)
        return TonalityReference(
            referenceName = "Approximate M-Noise-like curve",
            referenceKind = TonalityReferenceKind.ApproximateFallback,
            sourceSha256 = null,
            sourceMd5 = null,
            analysisVersion = ANALYSIS_VERSION,
            sampleRate = sampleRate,
            fftSize = config.fftSize,
            hopSize = config.hopSize,
            window = WINDOW_HANN,
            bandsPerOctave = config.bandsPerOctave,
            minHz = config.minHz,
            maxHz = config.maxHz,
            frequencyHz = layout.centerHz.copyOf(),
            referenceDb = FloatArray(layout.centerHz.size) { i -> approximateMNoiseReferenceDb(layout.centerHz[i]) }
        )
    }

    fun approximateMNoiseReferenceDb(freqHz: Float): Float {
        val octaveFrom1Khz = ln(freqHz / 1_000f) / ln(2f)
        return (-3f * octaveFrom1Khz).coerceIn(-14f, 14f)
    }
}
