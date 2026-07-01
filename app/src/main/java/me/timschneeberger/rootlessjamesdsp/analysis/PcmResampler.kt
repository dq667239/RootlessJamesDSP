package me.timschneeberger.rootlessjamesdsp.analysis

object PcmResampler {
    fun resampleInterleavedStereo(samples: FloatArray, sourceSampleRate: Int, targetSampleRate: Int): FloatArray {
        if (sourceSampleRate == targetSampleRate) return samples.copyOf()

        val sourceFrames = samples.size / CHANNELS
        if (sourceFrames <= 1) return samples.copyOf()

        val targetFrames = ((sourceFrames.toLong() * targetSampleRate) / sourceSampleRate).toInt().coerceAtLeast(1)
        val output = FloatArray(targetFrames * CHANNELS)
        val ratio = sourceSampleRate.toDouble() / targetSampleRate

        for (frame in 0 until targetFrames) {
            val sourcePosition = frame * ratio
            val sourceFrame = sourcePosition.toInt().coerceAtMost(sourceFrames - 1)
            val nextFrame = (sourceFrame + 1).coerceAtMost(sourceFrames - 1)
            val fraction = (sourcePosition - sourceFrame).toFloat()

            val sourceIndex = sourceFrame * CHANNELS
            val nextIndex = nextFrame * CHANNELS
            val outIndex = frame * CHANNELS
            output[outIndex] = lerp(samples[sourceIndex], samples[nextIndex], fraction)
            output[outIndex + 1] = lerp(samples[sourceIndex + 1], samples[nextIndex + 1], fraction)
        }

        return output
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private const val CHANNELS = 2
}
