package me.timschneeberger.rootlessjamesdsp.analysis

import java.io.InputStream
import kotlin.math.max

data class DecodedWavPcm(
    val displayName: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val interleavedStereo: FloatArray
)

object WavPcmDecoder {
    fun decode(inputStream: InputStream, displayName: String): DecodedWavPcm {
        val bytes = inputStream.use { it.readBytes() }
        require(bytes.size >= RIFF_HEADER_SIZE) { "File is too small to be a WAV" }
        require(bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WAVE") { "Unsupported WAV container" }

        var position = RIFF_HEADER_SIZE
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var blockAlign = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (position + CHUNK_HEADER_SIZE <= bytes.size) {
            val id = bytes.ascii(position, 4)
            val size = bytes.readIntLe(position + 4)
            val payload = position + CHUNK_HEADER_SIZE
            if (size < 0 || payload + size > bytes.size) break

            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV fmt chunk" }
                    audioFormat = bytes.readShortLe(payload)
                    channels = bytes.readShortLe(payload + 2)
                    sampleRate = bytes.readIntLe(payload + 4)
                    blockAlign = bytes.readShortLe(payload + 12)
                    bitsPerSample = bytes.readShortLe(payload + 14)
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                }
            }

            position = payload + size + (size and 1)
        }

        require(audioFormat == WAVE_FORMAT_PCM || audioFormat == WAVE_FORMAT_IEEE_FLOAT) {
            "Only PCM and 32-bit float WAV files are supported"
        }
        require(channels > 0) { "WAV has no channels" }
        require(sampleRate > 0) { "WAV has invalid sample rate" }
        require(dataOffset >= 0 && dataSize > 0) { "WAV has no audio data" }
        require(blockAlign > 0) { "WAV has invalid block alignment" }
        require(bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32) {
            "Unsupported WAV bit depth: $bitsPerSample"
        }
        require(audioFormat == WAVE_FORMAT_PCM || bitsPerSample == 32) {
            "Float WAV must be 32-bit"
        }

        val sourceFrames = dataSize / blockAlign
        val output = FloatArray(sourceFrames * OUTPUT_CHANNELS)
        val bytesPerSample = bitsPerSample / 8
        for (frame in 0 until sourceFrames) {
            val frameOffset = dataOffset + frame * blockAlign
            val left = readSample(bytes, frameOffset, bitsPerSample, audioFormat)
            val right = if (channels > 1) {
                readSample(bytes, frameOffset + bytesPerSample, bitsPerSample, audioFormat)
            }
            else {
                left
            }
            val outputOffset = frame * OUTPUT_CHANNELS
            output[outputOffset] = left
            output[outputOffset + 1] = right
        }

        val durationMs = sourceFrames * 1_000L / max(1, sampleRate)
        return DecodedWavPcm(
            displayName = displayName,
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            interleavedStereo = output
        )
    }

    private fun readSample(bytes: ByteArray, offset: Int, bitsPerSample: Int, audioFormat: Int): Float {
        return if (audioFormat == WAVE_FORMAT_IEEE_FLOAT) {
            Float.fromBits(bytes.readIntLe(offset)).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        }
        else {
            when (bitsPerSample) {
                16 -> bytes.readSigned16Le(offset) / 32768f
                24 -> bytes.readSigned24Le(offset) / 8388608f
                32 -> bytes.readIntLe(offset) / 2147483648f
                else -> 0f
            }
        }
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.readShortLe(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.readSigned16Le(offset: Int): Int {
        val value = readShortLe(offset)
        return if (value and 0x8000 != 0) value or -0x10000 else value
    }

    private fun ByteArray.readSigned24Le(offset: Int): Int {
        val value = (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)
        return if (value and 0x800000 != 0) value or -0x1000000 else value
    }

    private fun ByteArray.readIntLe(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }

    private const val RIFF_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val OUTPUT_CHANNELS = 2
    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_IEEE_FLOAT = 3
}
