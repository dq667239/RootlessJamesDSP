package me.timschneeberger.rootlessjamesdsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentLiveEqBinding
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import timber.log.Timber
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class LiveEqBottomSheet : BottomSheetDialogFragment() {

    private lateinit var binding: FragmentLiveEqBinding

    // Shared reference to the adapter's live band list — mutations here are reflected in the editor
    private lateinit var bands: ParametricEqBandList
    private var preampDb: Double = 0.0

    // Called on every slider change for immediate visual feedback
    private var onLiveUpdate: ((ParametricEqBandList) -> Unit)? = null

    // Called on slider touch end and on dismiss to persist + notify DSP
    private var onCommit: (() -> Unit)? = null

    private var selectedIndex: Int = -1

    // Guard against listener feedback loops when we set slider values programmatically
    private var isUpdatingSliders = false

    private val df = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    // ── Log mapping helpers ─────────────────────────────────────────────────

    private fun freqToSlider(hz: Double): Float =
        ((ln(hz) - LN_FREQ_MIN) / (LN_FREQ_MAX - LN_FREQ_MIN) * 100.0)
            .toFloat().coerceIn(0f, 100f)

    private fun sliderToFreq(pos: Float): Double =
        exp(LN_FREQ_MIN + pos / 100.0 * (LN_FREQ_MAX - LN_FREQ_MIN))

    private fun qToSlider(q: Double): Float =
        ((ln(q.coerceAtLeast(0.001)) - LN_Q_MIN) / (LN_Q_MAX - LN_Q_MIN) * 100.0)
            .toFloat().coerceIn(0f, 100f)

    private fun sliderToQ(pos: Float): Double =
        exp(LN_Q_MIN + pos / 100.0 * (LN_Q_MAX - LN_Q_MIN))

    // ── Fragment lifecycle ──────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentLiveEqBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initial surface render
        binding.liveEqSurface.setBands(bands, preampDb)

        // Build one chip per band
        buildBandChips()

        // Label formatters for floating labels
        binding.freqSlider.setLabelFormatter { pos ->
            "${sliderToFreq(pos).roundToInt()} Hz"
        }
        binding.gainSlider.setLabelFormatter { pos ->
            "${df.format(pos)} dB"
        }
        binding.qSlider.setLabelFormatter { pos ->
            df.format(sliderToQ(pos))
        }

        // Slider OnChangeListeners — update band and push visual feedback
        val freqChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            val newFreq = sliderToFreq(value)
            Timber.d("LiveEQ freqSlider: pos=$value → ${newFreq.roundToInt()} Hz")
            bands[selectedIndex] = ParametricEqBand(newFreq, band.gain, band.q, band.filterType, band.channel, band.uuid)
            refreshChipLabel(selectedIndex)
            onLiveUpdate?.invoke(bands)
            binding.liveEqSurface.setBands(bands, preampDb)
        }
        val gainChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            Timber.d("LiveEQ gainSlider: ${value} dB")
            bands[selectedIndex] = ParametricEqBand(band.frequency, value.toDouble(), band.q, band.filterType, band.channel, band.uuid)
            onLiveUpdate?.invoke(bands)
            binding.liveEqSurface.setBands(bands, preampDb)
        }
        val qChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            val newQ = sliderToQ(value).coerceAtLeast(0.1)
            Timber.d("LiveEQ qSlider: pos=$value → Q=$newQ")
            bands[selectedIndex] = ParametricEqBand(band.frequency, band.gain, newQ, band.filterType, band.channel, band.uuid)
            onLiveUpdate?.invoke(bands)
            binding.liveEqSurface.setBands(bands, preampDb)
        }

        binding.freqSlider.addOnChangeListener(freqChangeListener)
        binding.gainSlider.addOnChangeListener(gainChangeListener)
        binding.qSlider.addOnChangeListener(qChangeListener)

        // Commit to SharedPreferences only when the user lifts their finger
        val touchListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                onCommit?.invoke()
            }
        }
        binding.freqSlider.addOnSliderTouchListener(touchListener)
        binding.gainSlider.addOnSliderTouchListener(touchListener)
        binding.qSlider.addOnSliderTouchListener(touchListener)
    }

    override fun onDestroyView() {
        // Commit on swipe-dismiss to guarantee persistence
        onCommit?.invoke()
        super.onDestroyView()
    }

    // ── Band chips ──────────────────────────────────────────────────────────

    private fun buildBandChips() {
        binding.bandChips.removeAllViews()
        for ((i, band) in bands.withIndex()) {
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = chipLabel(i, band)
                isCheckable = true
                isCheckedIconVisible = false
            }
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectBand(i)
            }
            binding.bandChips.addView(chip)
        }
    }

    private fun chipLabel(index: Int, band: ParametricEqBand): String {
        val freqStr = if (band.frequency >= 1000.0)
            "${df.format(band.frequency / 1000.0)}k"
        else
            "${band.frequency.roundToInt()}"
        return "${index + 1}: ${band.filterType.displayLabel} ${freqStr}Hz"
    }

    private fun refreshChipLabel(index: Int) {
        val chip = binding.bandChips.getChildAt(index) as? Chip ?: return
        chip.text = chipLabel(index, bands[index])
    }

    private fun selectBand(index: Int) {
        selectedIndex = index
        val band = bands[index]

        isUpdatingSliders = true
        binding.freqSlider.value = freqToSlider(band.frequency)
        binding.gainSlider.value = band.gain.toFloat().coerceIn(-30f, 30f)
        binding.qSlider.value = qToSlider(band.q)
        isUpdatingSliders = false

        binding.liveEqHint.isVisible = false
        binding.slidersSection.isVisible = true
    }

    // ── Public API ──────────────────────────────────────────────────────────

    companion object {
        private val LN_FREQ_MIN = ln(20.0)
        private val LN_FREQ_MAX = ln(20000.0)
        private val LN_Q_MIN = ln(0.1)
        private val LN_Q_MAX = ln(30.0)

        fun newInstance(
            bands: ParametricEqBandList,
            preampDb: Double,
            onLiveUpdate: (ParametricEqBandList) -> Unit,
            onCommit: () -> Unit,
        ): LiveEqBottomSheet {
            return LiveEqBottomSheet().apply {
                this.bands = bands
                this.preampDb = preampDb
                this.onLiveUpdate = onLiveUpdate
                this.onCommit = onCommit
            }
        }
    }
}
