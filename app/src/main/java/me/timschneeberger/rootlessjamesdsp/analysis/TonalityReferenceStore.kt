package me.timschneeberger.rootlessjamesdsp.analysis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TonalityReferenceStore(private val context: Context) {
    fun load(sampleRate: Int): TonalityReference? {
        val file = referenceFile(sampleRate)
        if (!file.exists()) return null

        return runCatching {
            val json = JSONObject(file.readText())
            TonalityReference(
                referenceName = json.getString("referenceName"),
                referenceKind = TonalityReferenceKind.valueOf(json.getString("referenceKind")),
                sourceSha256 = json.optString("sourceSha256").ifBlank { null },
                sourceMd5 = json.optString("sourceMd5").ifBlank { null },
                analysisVersion = json.getInt("analysisVersion"),
                sampleRate = json.getInt("sampleRate"),
                fftSize = json.getInt("fftSize"),
                hopSize = json.getInt("hopSize"),
                window = json.getString("window"),
                bandsPerOctave = json.getInt("bandsPerOctave"),
                minHz = json.getDouble("minHz").toFloat(),
                maxHz = json.getDouble("maxHz").toFloat(),
                frequencyHz = json.getJSONArray("frequencyHz").toFloatArray(),
                referenceDb = json.getJSONArray("referenceDb").toFloatArray()
            )
        }.getOrNull()?.takeIf {
            it.analysisVersion == TonalityReferenceFactory.ANALYSIS_VERSION &&
                it.sampleRate == sampleRate &&
                it.frequencyHz.size == it.referenceDb.size
        }
    }

    fun loadBestFor(sampleRate: Int): TonalityReference {
        return load(sampleRate) ?: TonalityReferenceFactory.fallback(sampleRate)
    }

    fun save(reference: TonalityReference) {
        val file = referenceFile(reference.sampleRate)
        file.parentFile?.mkdirs()
        file.writeText(reference.toJson().toString())
    }

    fun referenceStatusLabel(sampleRate: Int = 48_000): String {
        return load(sampleRate)?.displayLabel ?: TonalityReferenceFactory.fallback(sampleRate).displayLabel
    }

    private fun referenceFile(sampleRate: Int): File {
        return File(File(context.filesDir, DIRECTORY), "reference_$sampleRate.json")
    }

    private fun TonalityReference.toJson(): JSONObject {
        return JSONObject()
            .put("referenceName", referenceName)
            .put("referenceKind", referenceKind.name)
            .put("sourceSha256", sourceSha256 ?: "")
            .put("sourceMd5", sourceMd5 ?: "")
            .put("analysisVersion", analysisVersion)
            .put("sampleRate", sampleRate)
            .put("fftSize", fftSize)
            .put("hopSize", hopSize)
            .put("window", window)
            .put("bandsPerOctave", bandsPerOctave)
            .put("minHz", minHz.toDouble())
            .put("maxHz", maxHz.toDouble())
            .put("frequencyHz", frequencyHz.toJsonArray())
            .put("referenceDb", referenceDb.toJsonArray())
    }

    private fun FloatArray.toJsonArray(): JSONArray {
        val array = JSONArray()
        for (value in this) {
            array.put(value.toDouble())
        }
        return array
    }

    private fun JSONArray.toFloatArray(): FloatArray {
        return FloatArray(length()) { i -> getDouble(i).toFloat() }
    }

    companion object {
        private const val DIRECTORY = "tonality_references"
    }
}
