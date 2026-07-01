package me.timschneeberger.rootlessjamesdsp.analysis

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class PreDspTonalityAnalyzer(
    private val reference: TonalityReference,
    private val onFrame: (TonalityFrame?) -> Unit,
    private val displayRateHz: Int = 5
) {
    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)
    private val frequencies = reference.frequencyHz.copyOf()
    private val config = TonalityAnalysisConfig(
        sampleRate = reference.sampleRate,
        fftSize = reference.fftSize,
        hopSize = reference.hopSize,
        bandsPerOctave = reference.bandsPerOctave,
        minHz = reference.minHz,
        maxHz = reference.maxHz
    )
    private val bandAnalyzer = StereoFftBandAnalyzer(config, TonalityBandLayout.create(config))
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
        bandAnalyzer.reset()
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
            val powerFrames = bandAnalyzer.offerInterleavedStereo(samples, samples.size)
            if (powerFrames.isEmpty()) continue

            for (bandPower in powerFrames) {
                val deviation = normalizeToReference(bandPower)
                for (i in deviation.indices) {
                    liveDeviationDb[i] = if (validAudioMs <= LIVE_WARMUP_MS || trackFrameCount == 0) {
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
            }

            val now = System.nanoTime()
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

    private fun normalizeToReference(bandPower: FloatArray): FloatArray {
        val rawDiff = FloatArray(bandPower.size) { i ->
            StereoFftBandAnalyzer.powerToDb(bandPower[i]) - reference.referenceDb[i]
        }
        val anchor = medianUsable(rawDiff)
        return FloatArray(rawDiff.size) { i -> rawDiff[i] - anchor }
    }

    private fun medianUsable(values: FloatArray): Float {
        val usable = ArrayList<Float>(values.size)
        val upper = minOf(12_000f, reference.maxHz)
        for (i in values.indices) {
            if (frequencies[i] in 40f..upper && values[i].isFinite()) {
                usable += values[i]
            }
        }
        if (usable.isEmpty()) {
            for (value in values) {
                if (value.isFinite()) usable += value
            }
        }
        usable.sort()
        val middle = usable.size / 2
        return if (usable.size % 2 == 0) {
            (usable[middle - 1] + usable[middle]) / 2f
        }
        else {
            usable[middle]
        }
    }

    private fun buildFrame(now: Long): TonalityFrame {
        val trackDeviationDb = FloatArray(frequencies.size) { i ->
            if (trackFrameCount > 0) trackSumDeviationDb[i] / trackFrameCount else liveDeviationDb[i]
        }
        val tonalBands = TonalBand.entries.associateWith { band ->
            averageAroundBand(trackDeviationDb, band.centerHz)
        }
        val score = StereoFftBandAnalyzer.rms(trackDeviationDb)

        return TonalityFrame(
            timestampNs = now,
            track = currentTrack,
            validAudioMs = validAudioMs,
            referenceName = reference.referenceName,
            referenceKind = reference.referenceKind,
            referenceIsVerified = reference.isVerified,
            referenceLabel = reference.displayLabel,
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

    private fun averageAroundBand(deviationDb: FloatArray, centerHz: Float): Float {
        val lower = centerHz / sqrt(2f)
        val upper = centerHz * sqrt(2f)
        var sum = 0f
        var count = 0
        for (i in frequencies.indices) {
            if (frequencies[i] in lower..upper) {
                sum += deviationDb[i]
                ++count
            }
        }
        if (count > 0) return sum / count

        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        for (i in frequencies.indices) {
            val distance = kotlin.math.abs(frequencies[i] - centerHz)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        return deviationDb[bestIndex]
    }

    private fun describe(bands: Map<TonalBand, Float>, deviationScoreDb: Float): String {
        if (validAudioMs < LIVE_WARMUP_MS) {
            return "Warming up... ${validAudioMs / 1000f}s valid audio"
        }

        val label = reference.displayLabel
        if (deviationScoreDb < NEUTRAL_SCORE_DB) {
            return "Near reference spectral balance vs $label. Deviation score: %.1f dB.".format(deviationScoreDb)
        }

        val strongestBoost = bands.maxBy { it.value }
        val strongestCut = bands.minBy { it.value }
        val tone = classifyTone(strongestBoost.key, strongestCut.key)

        return "%s: %+.1f dB %s, %+.1f dB %s vs %s. Deviation score: %.1f dB."
            .format(
                tone,
                strongestBoost.value,
                strongestBoost.key.label,
                strongestCut.value,
                strongestCut.key.label,
                label,
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
