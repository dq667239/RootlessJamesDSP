package me.timschneeberger.rootlessjamesdsp.analysis

data class TrackIdentity(
    val packageName: String? = null,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null
)

enum class TrackBoundaryReason {
    MetadataChanged,
    PlaybackRestarted,
    SessionChanged,
    SilenceThenResume,
    SpectralNovelty,
    ManualReset
}

data class TrackBoundaryEvent(
    val timestampNs: Long,
    val identity: TrackIdentity? = null,
    val confidence: Float,
    val reason: TrackBoundaryReason
)

enum class TonalBand(val label: String, val centerHz: Float) {
    Sub("sub", 40f),
    Bass("bass", 100f),
    LowMid("low-mid", 300f),
    Mid("mid", 1_000f),
    Presence("presence", 3_000f),
    Treble("treble", 7_000f),
    Air("air", 14_000f)
}

data class TonalityFrame(
    val timestampNs: Long,
    val track: TrackIdentity?,
    val validAudioMs: Long,
    val referenceName: String,
    val referenceKind: TonalityReferenceKind,
    val referenceIsVerified: Boolean,
    val referenceLabel: String,
    val frequencyHz: FloatArray,
    val liveDeviationDb: FloatArray,
    val trackDeviationDb: FloatArray,
    val tonalBandsDb: Map<TonalBand, Float>,
    val descriptor: String,
    val deviationScoreDb: Float,
    val confidence: Float,
    val isTransition: Boolean
)
