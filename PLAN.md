# M-Noise Reference Tonality Plan

## Goal

Replace the current hardcoded approximate M-Noise comparison with a reference-target system:

- Do not bundle the M-Noise WAV.
- Let the user import a local reference WAV.
- Verify official M-Noise checksums when applicable.
- Build app-private reference tables with the exact same analyzer path used for live pre-DSP audio.
- Label output as spectral deviation from the active reference, not psychoacoustic tonality or AES75 measurement.

## Current State

- `PreDspTonalityAnalyzer.kt` receives pre-DSP stereo PCM from `RootlessAudioProcessorService`.
- `RootlessAudioProcessorService.kt` already offers only the returned `AudioRecord.read(...)` length to analyzer, DSP, and `AudioTrack.write(...)`.
- Runtime analyzer currently estimates seven fixed bands with Goertzel probes.
- Runtime analyzer currently subtracts `approximateMNoiseReferenceDb(freqHz)`, a hardcoded -3 dB/octave-style curve.
- UI strings and descriptors currently say `approximate M-Noise`.
- Settings only expose `key_tonality_enabled`; there is no reference import/status UI.

## Verified External Facts

- Official M-Noise archive lists `MNoise_MSPN_90_916_049_15.wav` checksums:
  - MD5: `6539f08317d36216c3e0c37cf68c2b38`
  - SHA-256: `50bfcd4e5b9b7fcb07a0d34079198d076f978434c2971d80472c4c4ade66ec15`
- Official archive describes the file as an uncompressed 96 kHz WAV.
- Official archive includes EULA/trademark restrictions, so the app must not bundle the WAV and must not label unverified/altered files as verified M-Noise.

## Architecture

### Reference Models

Add `app/src/main/java/me/timschneeberger/rootlessjamesdsp/analysis/TonalityReference.kt`:

```kotlin
enum class TonalityReferenceKind {
    VerifiedMNoiseWav,
    ImportedReferenceWav,
    PinkNoiseDebug,
    MusicCorpusAverage,
    ApproximateFallback
}

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
)
```

Update `TonalityFrame` in `TonalityModels.kt` with active reference metadata:

```kotlin
val referenceName: String,
val referenceKind: TonalityReferenceKind,
val referenceIsVerified: Boolean
```

Use these fields for UI labels instead of hardcoded `approximate M-Noise`.

### File Verification

Add `MNoiseFileVerifier.kt`:

- Constants for official SHA-256 and MD5.
- `isVerifiedMNoise(sha256: String, md5: String?): Boolean`.
- Normalize hashes with lowercase hex.

Add a small hash utility, likely `ReferenceFileHasher.kt`:

- Stream the `Uri` from `ContentResolver`.
- Compute SHA-256 always.
- Compute MD5 only for M-Noise compatibility/status.
- Reopen the `Uri` for decode after hashing instead of buffering entire WAV in memory.

### WAV Decode

Add `WavPcmDecoder.kt` under `analysis` or a `reference` subpackage:

- Parse RIFF/WAVE chunks manually to avoid adding dependencies.
- Support PCM 16-bit, PCM 24-bit packed, PCM 32-bit int, and IEEE float 32-bit.
- Reject compressed WAV formats with a user-visible import failure.
- Preserve channel separation in decoded buffers.
- For mono input, duplicate mono to stereo for the analyzer path.
- For files with more than two channels, use the first two channels initially and log/import-label that only channels 1/2 were analyzed.
- Normalize samples as:
  - 16-bit PCM: `sample / 32768.0f`
  - 24-bit PCM: `sample / 8388608.0f`
  - 32-bit int: `sample / 2147483648.0f`
  - 32-bit float: use directly after sanity clamp to finite `[-1f, 1f]` for analysis safety.

### Shared Analyzer Pipeline

Replace Goertzel-only analysis with a shared FFT/band pipeline used by both reference build and live analyzer.

Add `TonalityAnalysisConfig.kt`:

```kotlin
data class TonalityAnalysisConfig(
    val sampleRate: Int,
    val fftSize: Int = 8192,
    val hopSize: Int = 2048,
    val bandsPerOctave: Int = 12,
    val minHz: Float = 20f,
    val maxHz: Float = minOf(20_000f, sampleRate / 2f)
)
```

Add `TonalityBandLayout.kt`:

- Generate 1/12-octave center frequencies from 20 Hz to `min(20 kHz, Nyquist)`.
- Generate lower/upper edges for each band.
- Keep existing `TonalBand` descriptor bands for text summary by mapping dense reference bands into the seven summary bands.

Add `StereoFftBandAnalyzer.kt`:

- Maintain Hann window table.
- Accept interleaved stereo float buffers and an exact `samplesRead` length.
- Buffer across offers until `fftSize` frames are available.
- Hop by `hopSize` frames.
- Compute left FFT and right FFT separately.
- Use stereo power average: `0.5 * (leftPower + rightPower)`.
- Integrate FFT-bin power into log bands.
- Average in linear power before dB conversion.
- Return per-window band power/dB frames.

Implementation options:

- Prefer a minimal in-project real FFT if no existing FFT utility exists.
- Keep it isolated behind `StereoFftBandAnalyzer` so it can be replaced with a native or library FFT later.
- Add JVM unit tests for sine/pink-style synthetic signals if the project test setup is usable.

### Reference Builder

Add `TonalityReferenceBuilder.kt`:

- Input: decoded stereo float PCM, source sample rate, target sample rate, source hashes, display name, verified flag.
- Resample decoded PCM to target sample rate before analysis.
- Build one table for `44100` and one for `48000` where feasible.
- Use the exact `StereoFftBandAnalyzer` config for each target sample rate.
- Average band powers in linear power over all valid windows.
- Convert final band means to dB.
- Return `TonalityReference`.

Add `PcmResampler.kt`:

- Start with deterministic linear interpolation because this is a reference-analysis path, not playback.
- Keep resampler behind a class so it can later be replaced by a higher-quality sinc/polyphase implementation.
- Validate M-Noise self-test tolerance after resampling before accepting this as final.

### Reference Storage

Add `TonalityReferenceStore.kt`:

- Store JSON in app-private storage, not shared preferences.
- Use existing `kotlinx.serialization-json` dependency if convenient.
- Suggested location: `filesDir/tonality_references/reference_44100.json` and `reference_48000.json`.
- Include `analysisVersion` and all analysis config fields.
- Provide:
  - `load(sampleRate: Int): TonalityReference?`
  - `save(reference: TonalityReference)`
  - `loadBestFor(sampleRate: Int): TonalityReference`
  - `fallback(sampleRate: Int): TonalityReference`

Fallback behavior:

- If no generated reference exists, use `ApproximateFallback` with the current approximate curve converted to the dense band layout.
- Label fallback as `approximate M-Noise-like curve`.

### Import UI

Update `app_misc_preferences.xml`:

- Add a `Preference` under Song tonality:
  - key: `key_tonality_import_reference`
  - title: `Import M-Noise/reference WAV`
  - summary: current status, e.g. `Reference: approximate M-Noise-like curve`.
- Add optional `Preference`:
  - key: `key_tonality_reference_status`
  - title: `Reference status`
  - summary updated by `SettingsMiscFragment`.

Update `keys.xml`, `strings.xml`, and defaults as needed.

Update `SettingsMiscFragment.kt`:

- Register `ActivityResultContracts.OpenDocument()` for `audio/wav`, `audio/x-wav`, and `audio/*` fallback.
- On import:
  - Hash selected file.
  - Decode WAV metadata and PCM.
  - Build 44.1 kHz and 48 kHz references.
  - Save references.
  - Persist only lightweight status fields in preferences if needed for summaries.
  - Show verified/unverified result.
- Do not persist URI access unless needed; the generated private reference table is the runtime artifact.

### Runtime Integration

Update `RootlessAudioProcessorService.kt`:

- On analyzer creation, load the active reference for the current clamped sample rate.
- Pass the `TonalityReference` into `PreDspTonalityAnalyzer`.
- Continue offering pre-DSP `shortBuffer`/`floatBuffer` with `usableSamples` only.
- If reference settings change while running, restart analyzer or recording similarly to `key_tonality_enabled`.

Update `PreDspTonalityAnalyzer.kt`:

- Constructor accepts `TonalityReference` and `TonalityAnalysisConfig`.
- Replace `estimateBandDb()` and `normalizeToApproximateMNoise()` with shared analyzer output.
- Runtime math:
  - `songBandDb[b] = 10 * log10(max(power[b], floor))`
  - `rawDiff[b] = songBandDb[b] - reference.referenceDb[b]`
  - `anchor = median(rawDiff over usable non-edge bands)`
  - `deviationDb[b] = rawDiff[b] - anchor`
- Maintain live EMA trace.
- Maintain track trace by averaging per-window deviations after silence/crossfade exclusion.
- Keep silence detection and media-session track reset behavior.
- Build `tonalBandsDb` from dense deviations by averaging into existing seven `TonalBand` regions.

### UI Labeling

Update `DspFragment.kt` and `card_song_tonality.xml`:

- Show active reference status in the card footer:
  - `Reference: verified M-Noise WAV`
  - `Reference: imported reference WAV`
  - `Reference: approximate M-Noise-like curve`
- Keep graph rendering generic; `MNoiseDeviationSurface` can remain named for now, but future rename to `ReferenceDeviationSurface` would be clearer.

Update analyzer descriptor strings:

- Replace `vs approximate M-Noise` with `vs {reference label}`.
- Avoid claims such as `psychoacoustic`, `neutral`, or `AES75 measurement`.
- Safer descriptor: `Near reference spectral balance. Deviation score: %.1f dB.`

## Implementation Sequence

1. Add reference metadata models, verifier, fallback reference, and UI labels without changing analyzer math.
2. Add shared dense band layout and FFT analyzer with synthetic tests.
3. Switch live analyzer from Goertzel probes to the shared FFT/band analyzer while still using `ApproximateFallback`.
4. Add WAV hash/decode/import flow and private JSON reference storage.
5. Add reference builder for 44.1/48 kHz tables and wire service loading.
6. Add validation tooling/tests for M-Noise self-analysis when a local WAV is available.
7. Polish UI wording and import-status summaries.

## Validation Plan

- Build: `./gradlew.bat assembleRootlessFullDebug`.
- Unit tests if added: `./gradlew.bat testRootlessFullDebugUnitTest`.
- Manual import test:
  - Import official M-Noise WAV.
  - Confirm SHA-256 or MD5 verified status.
  - Confirm no WAV copy is committed or bundled.
- Self-reference test:
  - Feed the imported M-Noise WAV through the live/reference analyzer path.
  - Expected deviation near 0 dB:
    - 40 Hz-12 kHz: within about +/-0.5 dB.
    - 20-40 Hz: within about +/-1.0 to +/-1.5 dB.
    - 12-20 kHz: within about +/-1.0 to +/-2.0 dB.
- Signal sanity tests:
  - Bass shelf should lift low-frequency deviations.
  - Low-pass should reduce treble/air deviations.
  - High-pass should reduce sub/bass deviations.
  - Pink noise should show the expected difference versus the active reference.

## Risks And Decisions

- FFT performance on the analyzer worker must be measured; if Kotlin FFT is too expensive, move FFT to native or lower display/update workload while keeping the same math.
- Linear resampling may not pass strict self-reference tolerances at band edges; keep it replaceable.
- Importing long WAVs can be memory-heavy if decoded all at once; prefer streaming decode into the reference builder if initial implementation shows memory pressure.
- Existing root service does not currently expose tonality frames; this plan keeps scope to rootless pre-DSP capture unless explicitly expanded.
- The `MNoiseDeviationSurface` class name is technically too specific once imported references exist, but renaming can be deferred to avoid a wide UI rename.

## Non-Goals

- Do not implement ISO 532, ECMA-418, or equal-loudness psychoacoustic modeling in this feature pass.
- Do not call unverified imported files `M-Noise`.
- Do not claim AES75 compliance or maximum linear SPL measurement.
- Do not bundle, generate, or redistribute the official WAV.

## Implementation Status

- Implemented reference metadata, fallback labels, official hash verifier, file hasher, private JSON reference store, WAV decoder, linear stereo resampler, reference builder, shared Hann-window FFT band analyzer, and dense 1/12-octave band layout.
- Switched `PreDspTonalityAnalyzer.kt` from seven Goertzel probes plus hardcoded approximate M-Noise normalization to the shared dense FFT/band path with an injected `TonalityReference`.
- Preserved existing pre-DSP rootless tap and partial-read handling in `RootlessAudioProcessorService.kt`; service now loads `TonalityReferenceStore.loadBestFor(sampleRate)` when creating the analyzer.
- Added Settings import/status UI under Song tonality. Import hashes the selected WAV, verifies official M-Noise checksums, decodes PCM, builds 44.1 kHz and 48 kHz private reference tables, and updates the active reference label.
- Updated song tonality card/status strings to describe deviation versus the active reference instead of hardcoded approximate M-Noise.
- Added JVM tests for official hash verification and FFT sine-band detection.
- Verified with `cmd /c gradlew.bat assembleRootlessFullDebug` and `cmd /c gradlew.bat testRootlessFullDebugUnitTest`.
- Remaining manual validation: import the real M-Noise WAV on-device and run the self-reference/obvious-signal checks because the repository does not contain the licensed WAV.
