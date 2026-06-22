package me.timschneeberger.rootlessjamesdsp.analysis

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class PreDspTonalityAnalyzer(
    private val onFrame: (TonalityFrame?) -> Unit,
    private val displayRateHz: Int = 5
) {
    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)
    private val frequencies = TonalBand.entries.map { it.centerHz }.toFloatArray()
    private val liveDeviationDb = FloatArray(frequencies.size)
    private val trackSumDeviationDb = FloatArray(frequencies.size)
    private var trackFrameCount = 0
    private var validAudioMs = 0L
    private var totalAudioMs = 0L
    private var silenceStartMs: Long? = null
    private var currentTrack: TrackIdentity? = null
    private var workerThread: Thread? = null

    @Volatile
    private var running = false

    fun start(sampleRate: Int) {
        stop()
        running = true
        validAudioMs = 0L
        totalAudioMs = 0L
        silenceStartMs = null
        trackFrameCount = 0
        liveDeviationDb.fill(0f)
        trackSumDeviationDb.fill(0f)
        queue.clear()
        onFrame(null)

        workerThread = Thread({ runWorker(sampleRate) }, "PreDspTonalityAnalyzer").also {
            it.priority = Thread.NORM_PRIORITY - 1
            it.start()
        }
    }

    fun stop() {
        running = false
        workerThread?.interrupt()
        try {
            workerThread?.join(STOP_JOIN_TIMEOUT_MS)
        }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workerThread = null
        queue.clear()
        onFrame(null)
    }

    fun resetTrack(event: TrackBoundaryEvent) {
        currentTrack = event.identity
        validAudioMs = 0L
        silenceStartMs = null
        trackFrameCount = 0
        trackSumDeviationDb.fill(0f)
    }

    fun offerPcm16(buffer: ShortArray, samplesRead: Int) {
        val usableSamples = samplesRead - (samplesRead % CHANNELS)
        if (usableSamples <= 0) return

        val copy = FloatArray(usableSamples)
        var i = 0
        while (i < usableSamples) {
            copy[i] = buffer[i] / PCM_16_SCALE
            ++i
        }
        offer(copy)
    }

    fun offerFloat(buffer: FloatArray, samplesRead: Int) {
        val usableSamples = samplesRead - (samplesRead % CHANNELS)
        if (usableSamples <= 0) return

        val copy = FloatArray(usableSamples)
        buffer.copyInto(copy, endIndex = usableSamples)
        offer(copy)
    }

    private fun offer(copy: FloatArray) {
        if (!queue.offer(copy)) {
            queue.poll()
            queue.offer(copy)
        }
    }

    private fun runWorker(sampleRate: Int) {
        val minFrameIntervalNs = 1_000_000_000L / max(1, displayRateHz)
        var lastFrameNs = 0L

        while (running) {
            val samples = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            }
            catch (_: InterruptedException) {
                break
            } ?: continue

            val frameCount = samples.size / CHANNELS
            if (frameCount <= 0) continue

            val frameDurationMs = frameCount * 1_000L / sampleRate
            totalAudioMs += frameDurationMs
            if (observeSilenceFallback(samples)) {
                continue
            }

            validAudioMs += frameDurationMs
            val songDb = estimateBandDb(samples, frameCount, sampleRate)
            val deviation = normalizeToApproximateMNoise(songDb)
            val now = System.nanoTime()

            for (i in deviation.indices) {
                liveDeviationDb[i] = if (validAudioMs <= LIVE_WARMUP_MS) {
                    deviation[i]
                }
                else {
                    (LIVE_EMA_ALPHA * deviation[i]) + ((1f - LIVE_EMA_ALPHA) * liveDeviationDb[i])
                }
            }

            if (validAudioMs > TRACK_TRANSITION_MS) {
                for (i in deviation.indices) {
                    trackSumDeviationDb[i] += deviation[i]
                }
                ++trackFrameCount
            }

            if (now - lastFrameNs >= minFrameIntervalNs) {
                lastFrameNs = now
                onFrame(buildFrame(now))
            }
        }
    }

    private fun observeSilenceFallback(samples: FloatArray): Boolean {
        val rmsDb = rmsDb(samples)
        if (rmsDb < SILENCE_THRESHOLD_DB) {
            if (silenceStartMs == null) {
                silenceStartMs = totalAudioMs
            }
            return true
        }

        val silenceDurationMs = silenceStartMs?.let { totalAudioMs - it } ?: 0L
        silenceStartMs = null

        if (silenceDurationMs > MIN_SILENCE_MS && validAudioMs > LIVE_WARMUP_MS) {
            resetTrack(
                TrackBoundaryEvent(
                    timestampNs = System.nanoTime(),
                    identity = currentTrack,
                    confidence = 0.55f,
                    reason = TrackBoundaryReason.SilenceThenResume
                )
            )
        }

        return false
    }

    private fun estimateBandDb(samples: FloatArray, frameCount: Int, sampleRate: Int): FloatArray {
        return FloatArray(frequencies.size) { i ->
            val freq = frequencies[i].coerceAtMost(sampleRate / 2f - 1f)
            val power = goertzelStereoPower(samples, frameCount, sampleRate, freq)
            10f * log10(power + POWER_FLOOR).toFloat()
        }
    }

    private fun goertzelStereoPower(samples: FloatArray, frameCount: Int, sampleRate: Int, freqHz: Float): Double {
        val omega = 2.0 * PI * freqHz / sampleRate
        val coeff = 2.0 * cos(omega)
        var leftQ0: Double
        var leftQ1 = 0.0
        var leftQ2 = 0.0
        var rightQ0: Double
        var rightQ1 = 0.0
        var rightQ2 = 0.0
        var i = 0

        while (i < frameCount) {
            val sampleIndex = i * CHANNELS
            leftQ0 = coeff * leftQ1 - leftQ2 + samples[sampleIndex]
            leftQ2 = leftQ1
            leftQ1 = leftQ0

            rightQ0 = coeff * rightQ1 - rightQ2 + samples[sampleIndex + 1]
            rightQ2 = rightQ1
            rightQ1 = rightQ0
            ++i
        }

        val leftPower = leftQ1 * leftQ1 + leftQ2 * leftQ2 - coeff * leftQ1 * leftQ2
        val rightPower = rightQ1 * rightQ1 + rightQ2 * rightQ2 - coeff * rightQ1 * rightQ2
        return 0.5 * (leftPower + rightPower) / max(1, frameCount)
    }

    private fun normalizeToApproximateMNoise(songDb: FloatArray): FloatArray {
        val rawDiff = FloatArray(songDb.size) { i ->
            songDb[i] - approximateMNoiseReferenceDb(frequencies[i])
        }
        val anchor = median(rawDiff)
        return FloatArray(rawDiff.size) { i -> rawDiff[i] - anchor }
    }

    private fun approximateMNoiseReferenceDb(freqHz: Float): Float {
        val octaveFrom1Khz = ln(freqHz / 1_000f) / ln(2f)
        return (-3f * octaveFrom1Khz).coerceIn(-14f, 14f)
    }

    private fun buildFrame(now: Long): TonalityFrame {
        val trackDeviationDb = FloatArray(frequencies.size) { i ->
            if (trackFrameCount > 0) trackSumDeviationDb[i] / trackFrameCount else liveDeviationDb[i]
        }
        val tonalBands = TonalBand.entries.withIndex().associate { (i, band) ->
            band to trackDeviationDb[i]
        }
        val score = rms(trackDeviationDb)

        return TonalityFrame(
            timestampNs = now,
            track = currentTrack,
            validAudioMs = validAudioMs,
            frequencyHz = frequencies.copyOf(),
            liveDeviationDb = liveDeviationDb.copyOf(),
            trackDeviationDb = trackDeviationDb,
            tonalBandsDb = tonalBands,
            descriptor = describe(tonalBands, score),
            deviationScoreDb = score,
            confidence = confidenceFor(validAudioMs),
            isTransition = validAudioMs <= TRACK_TRANSITION_MS
        )
    }

    private fun describe(bands: Map<TonalBand, Float>, deviationScoreDb: Float): String {
        if (validAudioMs < LIVE_WARMUP_MS) {
            return "Warming up... ${validAudioMs / 1000f}s valid audio"
        }

        if (deviationScoreDb < NEUTRAL_SCORE_DB) {
            return "Near-neutral vs approximate M-Noise. Deviation score: %.1f dB.".format(deviationScoreDb)
        }

        val strongestBoost = bands.maxBy { it.value }
        val strongestCut = bands.minBy { it.value }
        val tone = classifyTone(strongestBoost.key, strongestCut.key)

        return "%s: %+.1f dB %s, %+.1f dB %s vs approximate M-Noise. Deviation score: %.1f dB."
            .format(
                tone,
                strongestBoost.value,
                strongestBoost.key.label,
                strongestCut.value,
                strongestCut.key.label,
                deviationScoreDb
            )
    }

    private fun classifyTone(boost: TonalBand, cut: TonalBand): String {
        return when {
            boost == TonalBand.Bass || boost == TonalBand.Sub -> "Bass-heavy"
            boost == TonalBand.LowMid && cut == TonalBand.Air -> "Warm and dark"
            boost == TonalBand.Mid || boost == TonalBand.Presence -> "Mid-forward"
            boost == TonalBand.Treble || boost == TonalBand.Air -> "Bright"
            cut == TonalBand.Mid || cut == TonalBand.Presence -> "Scooped"
            else -> "Colored"
        }
    }

    private fun confidenceFor(validAudioMs: Long): Float {
        return (validAudioMs / 5_000f).coerceIn(0.1f, 1f)
    }

    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf().apply { sort() }
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
        else {
            sorted[middle]
        }
    }

    private fun rms(values: FloatArray): Float {
        var sum = 0.0
        for (value in values) {
            sum += (value * value).toDouble()
        }
        return sqrt(sum / values.size).toFloat()
    }

    private fun rmsDb(values: FloatArray): Float {
        return 20f * log10(rms(values).coerceAtLeast(POWER_FLOOR.toFloat()))
    }

    companion object {
        private const val CHANNELS = 2
        private const val QUEUE_CAPACITY = 8
        private const val STOP_JOIN_TIMEOUT_MS = 500L
        private const val PCM_16_SCALE = 32768f
        private const val POWER_FLOOR = 1.0e-12
        private const val LIVE_EMA_ALPHA = 0.22f
        private const val LIVE_WARMUP_MS = 1_000L
        private const val TRACK_TRANSITION_MS = 3_000L
        private const val NEUTRAL_SCORE_DB = 1.2f
        private const val SILENCE_THRESHOLD_DB = -55f
        private const val MIN_SILENCE_MS = 750L
    }
}
