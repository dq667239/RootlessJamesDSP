# Project Memory

## 2026-06-22 M-Noise Planning

- Repo had no existing project-level plan or memory file; created `PLAN.md` and `MEMORY.md` to satisfy persistent planning/memory workflow.
- Current tonality implementation is already pre-DSP in `RootlessAudioProcessorService.kt` and uses returned `AudioRecord.read(...)` lengths via `usableStereoSamples(...)` before analyzer, DSP, and write.
- Current analyzer is `PreDspTonalityAnalyzer.kt`; it uses seven fixed `TonalBand` centers, Goertzel stereo power, median anchoring, live EMA, track averaging, silence reset, and a hardcoded approximate M-Noise slope.
- UI pieces are `DspFragment.kt`, `MNoiseDeviationSurface.kt`, `card_song_tonality.xml`, `app_misc_preferences.xml`, `strings.xml`, `keys.xml`, and `defaults.xml`.
- Official M-Noise archive confirms the 96 kHz uncompressed WAV, EULA/trademark caution, MD5 `6539f08317d36216c3e0c37cf68c2b38`, and SHA-256 `50bfcd4e5b9b7fcb07a0d34079198d076f978434c2971d80472c4c4ade66ec15`.
- Important implementation constraint: do not bundle the WAV; import locally, verify hash, generate app-private reference tables, and label verified/unverified/fallback references accurately.
- Preferred implementation order: metadata/fallback labels, shared FFT/bands, live analyzer switch, import/decode/store, validation tooling, UI polish.
- Watch for memory/performance risks in Kotlin FFT, full-file WAV decode, and resampling quality.

## 2026-06-22 M-Noise Implementation

- Implemented the planned reference-target architecture without bundling the M-Noise WAV.
- New analysis files: `TonalityReference.kt`, `MNoiseFileVerifier.kt`, `ReferenceFileHasher.kt`, `StereoFftBandAnalyzer.kt`, `PcmResampler.kt`, `WavPcmDecoder.kt`, `TonalityReferenceBuilder.kt`, and `TonalityReferenceStore.kt`.
- Runtime analyzer now uses dense 1/12-octave FFT band powers, subtracts the active reference table, median-anchors usable bands, and maps dense deviations back to existing seven descriptor bands.
- Settings import flow lives in `SettingsMiscFragment.kt`; it builds both 44.1 kHz and 48 kHz app-private reference JSON files and stores only the active label in preferences.
- `RootlessAudioProcessorService.kt` loads the reference per clamped runtime sample rate and restarts recording when the reference label preference changes.
- Validation commands passed: `cmd /c gradlew.bat assembleRootlessFullDebug` and `cmd /c gradlew.bat testRootlessFullDebugUnitTest`.
- Manual validation still needed with the official M-Noise WAV because the licensed WAV is intentionally not in the repo.
