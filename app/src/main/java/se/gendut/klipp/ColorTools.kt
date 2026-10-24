package se.gendut.klipp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.LutStore
import se.gendut.klipp.engine.Qualifier
import se.gendut.klipp.engine.Window
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ------------------------------------------------------------------ node LUT

/** LUT in the selected node (this clip only). The LUT is applied after the node's other adjustments. */
@Composable
fun NodeLutEditor(n: GradeNode, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit, onImport: (((String) -> Unit) -> Unit)?) {
    // the LUT is read (and OpenDRT baked, 33³ points) on a background thread before the node is committed – never on the main thread
    val bake = rememberCoroutineScope()
    val pending = androidx.compose.runtime.remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val commitNow = onCommit
    @Suppress("NAME_SHADOWING")
    val onCommit: (GradeNode) -> Unit = { nn ->
        // latest only: two quick taps must never be committed in the wrong order
        pending[0]?.cancel()
        pending[0] = bake.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { nn.params() }
            commitNow(nn)
        }
    }
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            stringResource(R.string.colortools_lut_intro),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.colortools_none), n.lut == null, { onCommit(n.copy(lut = null)) }, Modifier.testTag("nodeLut:none"))
            for (name in LutStore.names) ToggleChip(Str.name(name), n.lut == name, { onCommit(n.copy(lut = name, lutMix = if (n.lut == null) 1f else n.lutMix)) }, Modifier.testTag("nodeLut:$name"))
        }
        if (onImport != null) PillButton(stringResource(R.string.colortools_import_cube), { onImport { name -> onCommit(n.copy(lut = name, lutMix = 1f)) } },
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("nodeLut:import"), filled = false)
        if (n.lut != null) {
            if (n.lut !in LutStore.names) Text(stringResource(R.string.colortools_lut_missing, n.lut.orEmpty()), color = K.Error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
            PropSlider(stringResource(R.string.colortools_lut_strength), n.lutMix, 0f, 1f, 1f, { "%.0f %%".format(it * 100) }, "nodeLutMix", lane = false,
                // with OpenDRT the LUT is baked together with the transform – it is rebuilt when the finger lifts
                onLive = { if (n.drt == null) onLive(n.copy(lutMix = it)) }) { onCommit(n.copy(lutMix = it)) }
        }
        DrtEditor(n, onCommit)
    }
}

/**
 * OpenDRT (Jed Smith, GPLv3) as a node effect: log footage → display image with a filmic tone curve and
 * color rendering. Applied after the node's adjustments and LUT – put it in the last node for an "output transform".
 */
@Composable
private fun DrtEditor(n: GradeNode, onCommit: (GradeNode) -> Unit) {
    val d = n.drt
    SectionTitle(stringResource(R.string.colortools_drt_title))
    Text(
        stringResource(R.string.colortools_drt_intro),
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(stringResource(R.string.colortools_off), d == null, { onCommit(n.copy(drt = null)) }, Modifier.testTag("drt:off"))
        ToggleChip(stringResource(R.string.colortools_on), d != null, { if (d == null) onCommit(n.copy(drt = se.gendut.klipp.engine.Drt())) }, Modifier.testTag("drt:on"))
    }
    if (d == null) return
    fun set(nd: se.gendut.klipp.engine.Drt) = onCommit(n.copy(drt = nd))
    SubLabel(stringResource(R.string.colortools_shot_in))
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (inp in se.gendut.klipp.engine.OpenDrt.Input.entries) ToggleChip(inp.label, d.input == inp.key, { set(d.copy(input = inp.key)) }, Modifier.testTag("drtIn:${inp.key}"))
    }
    SubLabel(stringResource(R.string.colortools_look))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        se.gendut.klipp.engine.OpenDrt.LOOKS.forEachIndexed { i, l -> ToggleChip(l, d.look == i, { set(d.copy(look = i)) }, Modifier.testTag("drtLook:$i")) }
    }
    SubLabel(stringResource(R.string.colortools_tonescale))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        se.gendut.klipp.engine.OpenDrt.TONESCALES.forEachIndexed { i, l -> ToggleChip(l, d.tonescale == i, { set(d.copy(tonescale = i)) }, Modifier.testTag("drtTs:$i")) }
    }
    SubLabel(stringResource(R.string.colortools_creative_white))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        se.gendut.klipp.engine.OpenDrt.WHITES.forEachIndexed { i, l -> ToggleChip(l, d.cwp == i, { set(d.copy(cwp = i)) }, Modifier.testTag("drtCwp:$i")) }
    }
    if (d.cwp != 0) PropSlider(stringResource(R.string.colortools_white_limit), d.cwpLimit, 0f, 1f, 0.25f, { "%.2f".format(it) }, "drtCwpLm", lane = false) { set(d.copy(cwpLimit = it)) }
    PropSlider(stringResource(R.string.colortools_mid_grey_nits), d.grey, 3f, 25f, 10f, { "%.1f".format(it) }, "drtGrey", lane = false) { set(d.copy(grey = it)) }
    SubLabel(stringResource(R.string.colortools_display))
    ConnectedToggleGroup(se.gendut.klipp.engine.OpenDrt.DISPLAYS, d.display, { set(d.copy(display = it)) }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
}

/**
 * Film (spektrafilm by Andrea Volpato et al., GPL-3.0; film profiles CC BY-SA 4.0): light exposes a negative,
 * which is developed and printed on paper (or the slide film is scanned) – spectrally, with the real film and paper
 * data. Last in the node, baked together with the node's LUT and OpenDRT.
 */
/** The film panel's commits outlive the panel (see [FilmEditor]). */
private object FilmBake {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    val pending = arrayOfNulls<kotlinx.coroutines.Job>(1)
}

@Composable
fun FilmEditor(n: GradeNode, onCommit: (GradeNode) -> Unit, onLive: ((GradeNode) -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    se.gendut.klipp.engine.Spektra.init(ctx)
    // baking (36,000 points through the whole chain) on a background thread; only the latest is committed. Not tied
    // to the panel: leaving the film tool (or the node) while it bakes must not lose the change – the player already
    // shows it (onLive)
    val bake = FilmBake.scope
    val pending = FilmBake.pending
    var baking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun commit(nn: GradeNode) {
        // the player shows the change while the finger is still on the slider (the commit waits for it to settle)
        onLive?.invoke(nn)
        pending[0]?.cancel()
        baking = true
        pending[0] = bake.launch {
            withContext(Dispatchers.Default) { nn.params() }
            onCommit(nn)
            baking = false
        }
    }
    val f = n.film
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            stringResource(R.string.colortools_film_intro),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleChip(stringResource(R.string.colortools_off), f == null, { commit(n.copy(film = null)) }, Modifier.testTag("film:off"))
            ToggleChip(stringResource(R.string.colortools_on), f != null, { if (f == null) commit(n.copy(film = se.gendut.klipp.engine.Film())) }, Modifier.testTag("film:on"))
            if (baking) Text(stringResource(R.string.colortools_computing), color = K.Outline, fontSize = 12.sp, modifier = Modifier.testTag("film:baking"))
        }
        if (f != null) {
            val idx = se.gendut.klipp.engine.Spektra.index
            fun set(nf: se.gendut.klipp.engine.Film) = commit(n.copy(film = nf))
            // while a slider is dragged: straight to the paused player (nothing is committed until release)
            fun live(nf: se.gendut.klipp.engine.Film) = onLive?.invoke(n.copy(film = nf))
            fun kind(s: se.gendut.klipp.engine.Spektra.StockInfo) = when {
                s.type == "positive" -> Str.t(R.string.colortools_kind_slide)
                s.use == "cine" -> Str.t(R.string.colortools_kind_cine_negative)
                else -> Str.t(R.string.colortools_kind_negative)
            }
            fun dec(v: Float, d: Int) = "%.${d}f".format(v)
            val off = stringResource(R.string.colortools_off_value)
            // ---- like spektrafilm's Simulation panel: profiles, input, exposure, enlarger
            // spektrafilm's defaults for this film (what "changed" in a section is measured against)
            val d = se.gendut.klipp.engine.Film(film = f.film, print = f.print, input = f.input, display = f.display)
            // the whole film, or only its effects on the image's own colors
            ConnectedToggleGroup(listOf(stringResource(R.string.colortools_film_mode_full), stringResource(R.string.colortools_film_mode_effects)),
                if (f.effectsOnly) 1 else 0, { set(f.copy(effectsOnly = it == 1)) }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp).testTag("film:mode"))
            if (f.effectsOnly) hint(stringResource(R.string.colortools_film_mode_effects_hint))
            // ---- the basics, always shown: what is simulated, what comes in, what goes out
            val fx = f.effectsOnly
            se.gendut.klipp.DropField(stringResource(if (fx) R.string.colortools_film_effects_from else R.string.colortools_film), idx.films.map { se.gendut.klipp.Opt(it.id, it.name, kind(it), key = it.id) }, f.film, "film:stock") { id ->
                val info = idx.films.first { it.id == id }
                // changing film: the film's own paper (slide film: no printing)
                set(f.copy(film = id, print = if (info.type == "positive") null else (info.targetPrint ?: f.print ?: "kodak_portra_endura")))
            }
            val positive = idx.films.firstOrNull { it.id == f.film }?.type == "positive"
            if (fx) {
                // effects only: no paper, exposure or display – the stock only decides the grain and the halation
                hint(stringResource(R.string.colortools_film_effects_from_hint))
            } else if (positive) {
                Text(stringResource(R.string.colortools_slide_scanned), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
            } else {
                val target = idx.films.firstOrNull { it.id == f.film }?.targetPrint
                val cinePrint = stringResource(R.string.colortools_print_cine)
                val photoPaper = stringResource(R.string.colortools_print_photo_paper)
                val filmsOwn = stringResource(R.string.colortools_print_films_own)
                se.gendut.klipp.DropField(stringResource(R.string.colortools_print_paper), se.gendut.klipp.engine.Spektra.printsFor(f.film).map {
                    se.gendut.klipp.Opt(it.id, it.name, listOfNotNull(if (it.support == "film") cinePrint else photoPaper, if (it.id == target) filmsOwn else null).joinToString(" · "), key = it.id)
                }, f.print ?: target ?: "kodak_portra_endura", "film:print") { set(f.copy(print = it)) }
            }
            se.gendut.klipp.DropField(stringResource(R.string.colortools_input_colorspace), se.gendut.klipp.engine.OpenDrt.Input.entries.map { se.gendut.klipp.Opt(it.key, it.label, key = it.key) }, f.input, "film:input") { set(f.copy(input = it)) }
            if (!fx) PropSlider(stringResource(R.string.colortools_exposure), f.ev, -3f, 3f, 0f, { "%+.1f EV".format(it) }, "filmEv", lane = false, onLive = { live(f.copy(ev = (it * 10).roundToInt() / 10f)) }) { set(f.copy(ev = (it * 10).roundToInt() / 10f)) }
            // (effects only: the image keeps its encoding – no display to choose)
            if (!f.effectsOnly) {
                SubLabel(stringResource(R.string.colortools_display))
                ConnectedToggleGroup(listOf(stringResource(R.string.colortools_display_phone_web), stringResource(R.string.colortools_display_tv)), f.display, { set(f.copy(display = it)) }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }

            // ---- the rest in the order of the chain, folded: camera → negative → print → scan
            FilmSection("camera", stringResource(R.string.colortools_camera),
                changed = f.format != d.format || f.lensBlur != d.lensBlur || f.diffusion != d.diffusion || f.diffusionStrength != d.diffusionStrength) {
                se.gendut.klipp.DropField(stringResource(R.string.colortools_film_format), FILM_FORMATS.map { (mm, name) -> se.gendut.klipp.Opt(mm, name, stringResource(R.string.colortools_film_format_width, if (mm % 1f == 0f) "%.0f".format(mm) else "%.1f".format(mm)), key = "f$mm") },
                    FILM_FORMATS.minByOrNull { kotlin.math.abs(it.first - f.format) }!!.first, "film:format") { set(f.copy(format = it)) }
                hint(stringResource(R.string.colortools_film_format_hint))
                PropSlider(stringResource(R.string.colortools_lens_blur), f.lensBlur, 0f, 60f, 0f, { "%.0f µm".format(it) }, "filmLens", lane = false, onLive = { live(f.copy(lensBlur = it.roundToInt().toFloat())) }) { set(f.copy(lensBlur = it.roundToInt().toFloat())) }
                se.gendut.klipp.DropField(stringResource(R.string.colortools_diffusion_camera),
                    listOf(se.gendut.klipp.Opt<String?>(null, stringResource(R.string.colortools_none_filter), key = "none")) + se.gendut.klipp.engine.FilmFx.FAMILIES.map { se.gendut.klipp.Opt<String?>(it.key, it.label, it.hint, key = it.key) },
                    f.diffusion, "film:diffusion") { set(f.copy(diffusion = it)) }
                if (f.diffusion != null) {
                    se.gendut.klipp.DropField(stringResource(R.string.colortools_filter_strength), se.gendut.klipp.engine.FilmFx.STRENGTHS.map { v -> se.gendut.klipp.Opt(v, strengthLabel(v), key = "s$v") },
                        se.gendut.klipp.engine.FilmFx.STRENGTHS.minByOrNull { kotlin.math.abs(it - f.diffusionStrength) }!!, "film:diffStrength") { set(f.copy(diffusionStrength = it)) }
                }
            }

            if (!fx) FilmSection("negative", stringResource(if (positive) R.string.colortools_section_film else R.string.colortools_section_negative),
                changed = f.gamma != d.gamma || f.couplers != d.couplers) {
                PropSlider(stringResource(R.string.colortools_film_gamma), f.gamma, 0.5f, 1.5f, 1f, { dec(it, 2) }, "filmGamma", lane = false, onLive = { live(f.copy(gamma = (it * 100).roundToInt() / 100f)) }) { set(f.copy(gamma = (it * 100).roundToInt() / 100f)) }
                PropSlider(stringResource(R.string.colortools_dir_amount), f.couplers, 0f, 1f, 1f, { "%.0f %%".format(it * 100) }, "filmDir", lane = false, onLive = { live(f.copy(couplers = (it * 100).roundToInt() / 100f)) }) { set(f.copy(couplers = (it * 100).roundToInt() / 100f)) }
            }

            FilmSection("halation", stringResource(R.string.colortools_halation),
                changed = f.halation != d.halation || f.halationSize != d.halationSize || f.scatter != d.scatter || f.scatterSize != d.scatterSize ||
                    f.boostEv != d.boostEv || f.protectEv != d.protectEv || f.boostRange != d.boostRange) {
                PropSlider(stringResource(R.string.colortools_halation_amount), f.halation, 0f, se.gendut.klipp.engine.Film.HALATION_MAX, 1f, { if (it < 0.05f) off else "${dec(it, 1)}×" }, "filmHal", lane = false, onLive = { live(f.copy(halation = (it * 10).roundToInt() / 10f)) }) { set(f.copy(halation = (it * 10).roundToInt() / 10f)) }
                PropSlider(stringResource(R.string.colortools_halation_size), f.halationSize, 0.25f, se.gendut.klipp.engine.Film.HALATION_SIZE_MAX, 1f, { "${dec(it, 2)}×" }, "filmHalSize", lane = false, onLive = { live(f.copy(halationSize = (it * 20).roundToInt() / 20f)) }) { set(f.copy(halationSize = (it * 20).roundToInt() / 20f)) }
                PropSlider(stringResource(R.string.colortools_scatter_amount), f.scatter, 0f, 2f, 1f, { "${dec(it, 2)}×" }, "filmScatter", lane = false, onLive = { live(f.copy(scatter = (it * 20).roundToInt() / 20f)) }) { set(f.copy(scatter = (it * 20).roundToInt() / 20f)) }
                PropSlider(stringResource(R.string.colortools_scatter_size), f.scatterSize, 0.25f, 4f, 1f, { "${dec(it, 2)}×" }, "filmScatterSize", lane = false, onLive = { live(f.copy(scatterSize = (it * 20).roundToInt() / 20f)) }) { set(f.copy(scatterSize = (it * 20).roundToInt() / 20f)) }
                PropSlider(stringResource(R.string.colortools_boost), f.boostEv, 0f, 12f, 0f, { if (it < 0.05f) off else "+${dec(it, 1)} EV" }, "filmBoost", lane = false, onLive = { live(f.copy(boostEv = (it * 10).roundToInt() / 10f)) }) { set(f.copy(boostEv = (it * 10).roundToInt() / 10f)) }
                if (f.boostEv > 0f) {
                    PropSlider(stringResource(R.string.colortools_protect), f.protectEv, 0f, 8f, 4f, { "${dec(it, 1)} EV" }, "filmProtect", lane = false, onLive = { live(f.copy(protectEv = (it * 10).roundToInt() / 10f)) }) { set(f.copy(protectEv = (it * 10).roundToInt() / 10f)) }
                    PropSlider(stringResource(R.string.colortools_boost_range), f.boostRange, 0f, 1f, 0.3f, { dec(it, 2) }, "filmBoostRange", lane = false, onLive = { live(f.copy(boostRange = (it * 100).roundToInt() / 100f)) }) { set(f.copy(boostRange = (it * 100).roundToInt() / 100f)) }
                }
                hint(stringResource(R.string.colortools_boost_hint))
            }

            // all of the grain in one place: spektrafilm's controls first, then Klipp's additions
            FilmSection("grain", stringResource(R.string.colortools_grain), summary = if (f.grain) null else off,
                changed = f.grain != d.grain || f.grainLayers != d.grainLayers || f.grainSize != d.grainSize || f.grainBlur != d.grainBlur ||
                    f.grainAmount != d.grainAmount || f.grainClouds != d.grainClouds || f.grainColor != d.grainColor) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip(stringResource(R.string.colortools_grain), f.grain, { set(f.copy(grain = !f.grain)) }, Modifier.testTag("film:grain"))
                    if (f.grain) ToggleChip(stringResource(R.string.colortools_sublayers), f.grainLayers, { set(f.copy(grainLayers = !f.grainLayers)) }, Modifier.testTag("film:grainLayers"))
                }
                if (f.grain) {
                    PropSlider(stringResource(R.string.colortools_grain_area), f.grainSize * 0.2f, 0.05f, 0.8f, 0.2f, { dec(it, 2) }, "filmGrain", lane = false, onLive = { live(f.copy(grainSize = ((it / 0.2f) * 20).roundToInt() / 20f)) }) { set(f.copy(grainSize = ((it / 0.2f) * 20).roundToInt() / 20f)) }
                    PropSlider(stringResource(R.string.colortools_grain_blur), f.grainBlur, 0f, 3f, 0.65f, { dec(it, 2) }, "filmGrainBlur", lane = false, onLive = { live(f.copy(grainBlur = (it * 100).roundToInt() / 100f)) }) { set(f.copy(grainBlur = (it * 100).roundToInt() / 100f)) }
                    hint(stringResource(R.string.colortools_grain_hint))
                    PropSlider(stringResource(R.string.colortools_grain_amount), f.grainAmount, 0f, 8f, 1f, { if (it < 0.01f) off else "${dec(it, 1)}×" }, "filmGrainAmount", lane = false, onLive = { live(f.copy(grainAmount = (it * 10).roundToInt() / 10f)) }) { set(f.copy(grainAmount = (it * 10).roundToInt() / 10f)) }
                    PropSlider(stringResource(R.string.colortools_color_clouds), f.grainClouds, 0f, 3f, 0f, { if (it < 0.01f) off else "${dec(it, 1)}×" }, "filmClouds", lane = false, onLive = { live(f.copy(grainClouds = (it * 10).roundToInt() / 10f)) }) { set(f.copy(grainClouds = (it * 10).roundToInt() / 10f)) }
                    PropSlider(stringResource(R.string.colortools_grain_color), f.grainColor, 0f, 1f, 1f, { "%.0f %%".format(it * 100) }, "filmGrainColor", lane = false, onLive = { live(f.copy(grainColor = (it * 20).roundToInt() / 20f)) }) { set(f.copy(grainColor = (it * 20).roundToInt() / 20f)) }
                    hint(stringResource(R.string.colortools_extras_hint))
                }
            }

            if (!positive) {
                FilmSection("print", stringResource(R.string.colortools_section_print),
                    changed = (!fx && (f.labComp != d.labComp || f.printExposure != d.printExposure || f.yShift != d.yShift || f.mShift != d.mShift || f.printGamma != d.printGamma)) ||
                        f.glare != d.glare || f.glarePercent != d.glarePercent || f.glareRoughness != d.glareRoughness ||
                        f.enlargerDiffusion != d.enlargerDiffusion || f.enlargerDiffusionStrength != d.enlargerDiffusionStrength) {
                    // effects only: of the print only its effects (the enlarger's diffusion filter, glare)
                    if (!fx) Row(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToggleChip(stringResource(R.string.colortools_print_auto_comp), f.labComp, { set(f.copy(labComp = !f.labComp)) }, Modifier.testTag("film:lab"))
                    }
                    if (!fx) {
                        hint(
                            if (f.labComp) stringResource(R.string.colortools_print_auto_comp_on_hint)
                            else stringResource(R.string.colortools_print_auto_comp_off_hint),
                        )
                        val stops = kotlin.math.log2(f.printExposure.coerceAtLeast(0.01f))
                        PropSlider(stringResource(R.string.colortools_print_exposure), stops, -2f, 2f, 0f, { "${dec(2f.pow(it), 2)}× (${"%+.2f".format(it)} EV)" }, "filmPrint", lane = false,
                            onLive = { live(f.copy(printExposure = 2f.pow((it * 20).roundToInt() / 20f))) }) {
                            set(f.copy(printExposure = 2f.pow((it * 20).roundToInt() / 20f)))
                        }
                    }
                    SubLabel(stringResource(R.string.colortools_enlarger))
                    if (!fx) {
                        PropSlider(stringResource(R.string.colortools_enlarger_y), f.yShift, -100f, 100f, 0f, { "%+.0f".format(it) }, "filmY", lane = false, onLive = { live(f.copy(yShift = it.roundToInt().toFloat())) }) { set(f.copy(yShift = it.roundToInt().toFloat())) }
                        PropSlider(stringResource(R.string.colortools_enlarger_m), f.mShift, -100f, 100f, 0f, { "%+.0f".format(it) }, "filmM", lane = false, onLive = { live(f.copy(mShift = it.roundToInt().toFloat())) }) { set(f.copy(mShift = it.roundToInt().toFloat())) }
                    }
                    se.gendut.klipp.DropField(stringResource(R.string.colortools_diffusion_enlarger),
                        listOf(se.gendut.klipp.Opt<String?>(null, stringResource(R.string.colortools_none_filter), key = "none")) + se.gendut.klipp.engine.FilmFx.FAMILIES.map { se.gendut.klipp.Opt<String?>(it.key, it.label, it.hint, key = it.key) },
                        f.enlargerDiffusion, "film:enlargerDiffusion") { set(f.copy(enlargerDiffusion = it)) }
                    if (f.enlargerDiffusion != null) {
                        se.gendut.klipp.DropField(stringResource(R.string.colortools_filter_strength), se.gendut.klipp.engine.FilmFx.STRENGTHS.map { v -> se.gendut.klipp.Opt(v, strengthLabel(v), key = "e$v") },
                            se.gendut.klipp.engine.FilmFx.STRENGTHS.minByOrNull { kotlin.math.abs(it - f.enlargerDiffusionStrength) }!!, "film:enlargerStrength") { set(f.copy(enlargerDiffusionStrength = it)) }
                    }
                    if (!fx) {
                        SubLabel(stringResource(R.string.colortools_chemistry))
                        PropSlider(stringResource(R.string.colortools_paper_gamma), f.printGamma, 0.5f, 2f, 1f, { dec(it, 2) }, "filmPrintGamma", lane = false, onLive = { live(f.copy(printGamma = (it * 100).roundToInt() / 100f)) }) { set(f.copy(printGamma = (it * 100).roundToInt() / 100f)) }
                        hint(stringResource(R.string.colortools_paper_gamma_hint))
                    }
                    SubLabel(stringResource(R.string.colortools_glare))
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        ToggleChip(stringResource(R.string.colortools_glare_in_paper), f.glare, { set(f.copy(glare = !f.glare)) }, Modifier.testTag("film:glare"))
                    }
                    if (f.glare) {
                        PropSlider(stringResource(R.string.colortools_glare_amount), f.glarePercent, 0f, 1f, 0.03f, { dec(it, 2) }, "filmGlare", lane = false, onLive = { live(f.copy(glarePercent = (it * 100).roundToInt() / 100f)) }) { set(f.copy(glarePercent = (it * 100).roundToInt() / 100f)) }
                        PropSlider(stringResource(R.string.colortools_glare_roughness), f.glareRoughness, 0f, 1f, 0.7f, { dec(it, 2) }, "filmGlareRough", lane = false, onLive = { live(f.copy(glareRoughness = (it * 100).roundToInt() / 100f)) }) { set(f.copy(glareRoughness = (it * 100).roundToInt() / 100f)) }
                    }
                }
            }

            FilmSection("scanner", stringResource(R.string.colortools_scanner), changed = f.scanBlur != d.scanBlur || f.sharpen != d.sharpen) {
                PropSlider(stringResource(R.string.colortools_scan_blur), f.scanBlur, 0f, 3f, 0f, { dec(it, 2) }, "filmScanBlur", lane = false, onLive = { live(f.copy(scanBlur = (it * 20).roundToInt() / 20f)) }) { set(f.copy(scanBlur = (it * 20).roundToInt() / 20f)) }
                PropSlider(stringResource(R.string.colortools_scan_sharpen), f.sharpen, 0f, 2f, 0.7f, { dec(it, 2) }, "filmSharp", lane = false, onLive = { live(f.copy(sharpen = (it * 20).roundToInt() / 20f)) }) { set(f.copy(sharpen = (it * 20).roundToInt() / 20f)) }
            }

            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                ToggleChip(stringResource(R.string.colortools_spektrafilm_defaults), false, {
                    set(se.gendut.klipp.engine.Film(film = f.film, print = f.print, input = f.input, display = f.display, effectsOnly = f.effectsOnly))
                }, Modifier.testTag("film:defaults"))
            }
            hint(stringResource(R.string.colortools_spektrafilm_defaults_hint))
            Text(
                stringResource(R.string.colortools_spektrafilm_parity),
                color = K.OnSurfaceVar, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.colortools_spektrafilm_credit),
                color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** Which of the film editor's sections are open (kept while switching nodes and clips). */
private val filmSectionsOpen = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

/**
 * A folded section of the film editor: a header (title, a dot when something in it differs from spektrafilm's
 * defaults, an optional short state such as "Off") that opens and closes [content].
 */
@Composable
private fun FilmSection(key: String, title: String, changed: Boolean, summary: String? = null, content: @Composable () -> Unit) {
    val open = filmSectionsOpen[key] ?: false
    val haptics = rememberHaptics()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(if (open) K.SurfaceHighest.copy(alpha = 0.55f) else K.SurfaceHigh.copy(alpha = 0.6f)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { haptics.tick(); filmSectionsOpen[key] = !open }.padding(horizontal = 6.dp, vertical = 12.dp)
                .testTag("filmsec:$key"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = K.Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (changed) Box(Modifier.padding(start = 8.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(K.Primary).testTag("filmsec:$key:changed"))
            Box(Modifier.weight(1f))
            if (summary != null) Text(summary, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            androidx.compose.material3.Icon(Ic.ChevronDown, null, tint = K.OnSurfaceVar,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (open) 180f else 0f })
        }
        if (open) Column(Modifier.padding(bottom = 6.dp)) { content() }
    }
}

/** Film formats (longest side in mm) and names. A getter so the names follow the app language. */
private val FILM_FORMATS get() = listOf(
    5.8f to "Super 8",
    12.5f to "Super 16",
    24.9f to Str.t(R.string.colortools_format_super35),
    35f to Str.t(R.string.colortools_format_35mm),
    52.5f to "65 mm",
    70f to Str.t(R.string.colortools_format_medium),
)

@Composable
private fun hint(text: String) = Text(text, color = K.OnSurfaceVar, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))

private fun strengthLabel(v: Float) = when (v) { 0.125f -> "1/8"; 0.25f -> "1/4"; 0.5f -> "1/2"; 1f -> "1"; 2f -> "2"; else -> v.toString() }

@Composable
private fun SectionTitle(text: String) = Text(text, color = K.Primary, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 2.dp))

@Composable
private fun SubLabel(text: String) = Text(text, color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, top = 8.dp))

// ------------------------------------------------------------------ qualifier

@Composable
fun QualifierEditor(clip: Long, n: GradeNode, gs: GradeState, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit, onPick: () -> Unit) {
    val q = n.qual
    fun set(f: Qualifier, live: Boolean = false) { val nn = n.copy(qual = f); if (live) onLive(nn) else onCommit(nn) }
    val keyShown = gs.showKey == (clip to n.id)
    Column {
        PillButton(if (gs.pick != null) stringResource(R.string.colortools_tap_image) else stringResource(R.string.colortools_pick_with_dropper), onPick,
            Modifier.padding(horizontal = 14.dp, vertical = 4.dp).testTag("qual:pick"), icon = Ic.Dropper, filled = gs.pick == null)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.colortools_on), q.on, { set(q.copy(on = !q.on)) }, Modifier.testTag("qual:on"))
            ToggleChip(stringResource(R.string.colortools_show_key), keyShown, { gs.showKey = if (keyShown) null else clip to n.id }, Modifier.testTag("qual:key"))
            ToggleChip(stringResource(R.string.colortools_invert), q.invert, { set(q.copy(invert = !q.invert, on = true)) }, Modifier.testTag("qual:inv"))
        }
        if (!q.on) {
            Text(stringResource(R.string.colortools_qual_intro), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        } else {
            // color strip showing which hue is selected
            HueStrip(q)
            val all = stringResource(R.string.colortools_hue_all)
            PropSlider(stringResource(R.string.colortools_hue), q.hue, 0f, 360f, 0f, { "%.0f°".format(it) }, "qHue", onLive = { set(q.copy(hue = it), true) }) { set(q.copy(hue = it)) }
            PropSlider(stringResource(R.string.colortools_hue_width), q.hueWidth, 2f, 360f, 360f, { if (it >= 359.5f) all else "%.0f°".format(it) }, "qHueW", onLive = { set(q.copy(hueWidth = it), true) }) { set(q.copy(hueWidth = it)) }
            PropSlider(stringResource(R.string.colortools_sat_from), q.satLo, 0f, 1f, 0f, { "%.0f".format(it * 100) }, "qSatLo", onLive = { set(q.copy(satLo = min(it, q.satHi)), true) }) { set(q.copy(satLo = min(it, q.satHi))) }
            PropSlider(stringResource(R.string.colortools_sat_to), q.satHi, 0f, 1f, 1f, { "%.0f".format(it * 100) }, "qSatHi", onLive = { set(q.copy(satHi = max(it, q.satLo)), true) }) { set(q.copy(satHi = max(it, q.satLo))) }
            PropSlider(stringResource(R.string.colortools_luma_from), q.lumLo, 0f, 1f, 0f, { "%.0f".format(it * 100) }, "qLumLo", onLive = { set(q.copy(lumLo = min(it, q.lumHi)), true) }) { set(q.copy(lumLo = min(it, q.lumHi))) }
            PropSlider(stringResource(R.string.colortools_luma_to), q.lumHi, 0f, 1f, 1f, { "%.0f".format(it * 100) }, "qLumHi", onLive = { set(q.copy(lumHi = max(it, q.lumLo)), true) }) { set(q.copy(lumHi = max(it, q.lumLo))) }
            PropSlider(stringResource(R.string.colortools_softness), q.soft, 0f, 0.5f, 0.1f, { "%.0f".format(it * 200) }, "qSoft", onLive = { set(q.copy(soft = it), true) }) { set(q.copy(soft = it)) }
        }
    }
}

@Composable
private fun HueStrip(q: Qualifier) {
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).aspectRatio(18f).clip(RoundedCornerShape(6.dp))) {
        val n = 72
        for (i in 0 until n) {
            val h = i / n.toFloat()
            val c = android.graphics.Color.HSVToColor(floatArrayOf(h * 360f, 0.85f, 0.95f))
            drawRect(Color(c), Offset(size.width * i / n, 0f), Size(size.width / n + 1f, size.height))
        }
        if (q.hueWidth < 359.5f) {
            // outside the selection: dark
            val c = q.hue / 360f; val half = q.hueWidth / 720f
            for (i in 0 until n) {
                val h = (i + 0.5f) / n
                val dh = abs(((h - c + 0.5f) - kotlin.math.floor(h - c + 0.5f)) - 0.5f)
                if (dh > half) drawRect(Color(0xAA000000), Offset(size.width * i / n, 0f), Size(size.width / n + 1f, size.height))
            }
        }
    }
}

// ------------------------------------------------------------------ window

/**
 * Window tracking (keeps running if you switch tabs). One job at a time; the result lands in the node
 * the tracking was started for, even if another clip has been selected meanwhile.
 */
object TrackService {
    var progress by androidx.compose.runtime.mutableStateOf<Float?>(null)
        private set
    /** (clip, node) being tracked right now. */
    var target by androidx.compose.runtime.mutableStateOf<Pair<Long, Int>?>(null)
        private set
    var message by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The tracking so far (while it runs) – the window is drawn along it, as in Resolve. */
    var partial by androidx.compose.runtime.mutableStateOf<se.gendut.klipp.engine.WindowTrack?>(null)
        private set
    /** The window the ongoing tracking starts from (the tracking is relative to it). */
    var partialBase by androidx.compose.runtime.mutableStateOf<se.gendut.klipp.engine.Window?>(null)
        private set
    /** Point clouds per (clip, node): the latest tracking (and the ongoing one). Memory only. */
    val clouds = androidx.compose.runtime.mutableStateMapOf<Pair<Long, Int>, se.gendut.klipp.engine.TrackCloud>()
    private val scope = kotlinx.coroutines.MainScope()
    private var job: kotlinx.coroutines.Job? = null

    /**
     * Track the node's window from the frame at [playhead]. [follow] moves the playhead (timeline seconds)
     * to the frame being tracked right now, so you see the window and the points follow along.
     */
    /**
     * @param continueFrom track only forward from the playhead and keep the tracking before it (after a manual
     * correction: the window where it is now becomes the starting point)
     */
    fun start(ctx: Context, gs: GradeState, c: ColorClip, nodeId: Int, playhead: Float, follow: ((Float) -> Unit)? = null, continueFrom: Boolean = false) {
        val uri = c.uri ?: run { message = Str.t(R.string.colortools_clip_file_missing); return }
        val node = gs.shown(c.id)?.nodes?.firstOrNull { it.id == nodeId } ?: return
        if (!node.win.on) return
        val old = node.win.track.takeIf { continueFrom }
        cancel()
        val lo = c.srcIn
        val hi = c.srcIn + (c.end - c.start) * kotlin.math.abs(c.speed)
        val ref = c.srcAt(playhead).coerceIn(lo, hi)
        // the window where it shows now (a previous tracking baked in) becomes the reference for the new one
        val base = node.win.at(ref)
        val key = c.id to nodeId
        target = key
        partialBase = base
        progress = 0f
        message = null
        clouds.remove(key)
        val samples = java.util.concurrent.ConcurrentLinkedQueue<se.gendut.klipp.engine.WindowTracker.Sample>()
        // latest frame forward and backward respectively (written by the tracking threads)
        val fwd = java.util.concurrent.atomic.AtomicReference<Double?>(null)
        val bwd = java.util.concurrent.atomic.AtomicReference<Double?>(null)
        fun toTimeline(src: Double) = (c.start + (src - c.srcIn) / kotlin.math.abs(c.speed).coerceAtLeast(1e-6)).toFloat().coerceIn(c.start, c.end)
        job = scope.launch {
            // progress in the image: a few times per second
            val ticker = launch {
                var lastF: Double? = null
                var stall = 0
                while (true) {
                    kotlinx.coroutines.delay(200)
                    val list = samples.toList()
                    if (list.isEmpty()) continue
                    val sorted = list.sortedBy { it.t }
                    partial = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { se.gendut.klipp.engine.WindowTracker.merge(sorted) }.getOrNull() }
                    clouds[key] = se.gendut.klipp.engine.TrackCloud(sorted)
                    // forward first (what you usually look at), then backward
                    // (forward counts as done once it has stalled a couple of times)
                    val f = fwd.get(); val b = bwd.get()
                    stall = if (f == lastF) stall + 1 else 0
                    lastF = f
                    (if (f != null && stall < 2) f else b ?: f)?.let { t -> follow?.invoke(toTimeline(t)) }
                }
            }
            try {
                val t0 = System.nanoTime()
                val fresh = se.gendut.klipp.engine.WindowTracker.track(ctx, uri, ref, lo, hi, base, { p -> scope.launch { progress = p } },
                    onSample = { smp ->
                        samples += smp
                        if (smp.t >= ref) fwd.set(smp.t) else bwd.set(smp.t)
                    }, forwardOnly = old != null)
                // continue from here: the tracking before the playhead stays, the rest is replaced (aligned where they meet)
                val track = old?.replaceFrom(fresh) ?: fresh
                ticker.cancel()
                clouds[key] = se.gendut.klipp.engine.TrackCloud(samples.toList())
                val cur = gs.shown(c.id)?.nodes?.firstOrNull { it.id == nodeId } ?: return@launch
                val sm = cur.win.track?.smooth ?: 0.3f
                gs.commitNode(c.id, cur.copy(win = if (old != null) cur.win.copy(track = track.withSmooth(sm))
                    else base.copy(soft = cur.win.soft, invert = cur.win.invert, shape = cur.win.shape, track = track.withSmooth(sm))))
                gs.endLive()
                follow?.invoke(playhead)
                val secs = (System.nanoTime() - t0) / 1e9
                message = Str.t(R.string.colortools_tracked, track.end - track.t0, secs) +
                    (if (track.weak > 0) " · " + Str.p(R.plurals.colortools_uncertain_frames, track.weak, track.weak) else "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = Str.t(R.string.colortools_tracking_cancelled)
                clouds.remove(key)
                throw e
            } catch (e: Throwable) {
                message = Str.t(R.string.colortools_tracking_failed, e.message ?: e.javaClass.simpleName)
                clouds.remove(key)
            } finally {
                ticker.cancel()
                progress = null
                target = null
                partial = null
                partialBase = null
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }
}

/** The node as a vignette: a soft inverted ellipse filling the picture; the gain goes down outside it (unless already set). */
fun vignette(n: GradeNode): GradeNode = n.copy(
    win = Window(shape = 1, cx = 0.5f, cy = 0.5f, rx = 0.6f, ry = 0.6f, soft = 1f, invert = true),
    gain = if (n.gain.master == 0f) n.gain.copy(master = -0.4f) else n.gain,
)

@Composable
fun WindowEditor(clip: Long, n: GradeNode, gs: GradeState, onLive: (GradeNode) -> Unit, onCommit: (GradeNode) -> Unit,
                 colorClip: ColorClip? = null, playhead: () -> Float = { 0f }, seek: ((Float) -> Unit)? = null) {
    val w = n.win
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun set(f: Window, live: Boolean = false) { val nn = n.copy(win = f); if (live) onLive(nn) else onCommit(nn) }
    val keyShown = gs.showKey == (clip to n.id)
    Column {
        ConnectedToggleGroup(listOf(stringResource(R.string.colortools_off), stringResource(R.string.colortools_circle), stringResource(R.string.colortools_rectangle)), w.shape, { set(w.copy(shape = it)) },
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), tag = { "win:$it" })
        if (w.on) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.colortools_invert), w.invert, { set(w.copy(invert = !w.invert)) }, Modifier.testTag("win:inv"))
            ToggleChip(stringResource(R.string.colortools_show_key), keyShown, { gs.showKey = if (keyShown) null else clip to n.id }, Modifier.testTag("win:key"))
        }
        if (!w.on) {
            Text(stringResource(R.string.colortools_window_intro), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            // a vignette: a soft inverted ellipse the size of the picture, with the gain lowered outside it – then shaped
            // with the window's sliders and darkened/lightened with the node's gain
            PillButton(stringResource(R.string.colortools_vignette), { onCommit(vignette(n)) }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("win:vignette"), icon = Ic.Window, filled = false)
            Text(stringResource(R.string.colortools_vignette_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        } else {
            Text(stringResource(R.string.colortools_window_drag_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            PropSlider(stringResource(R.string.colortools_center_x), w.cx * 100, 0f, 100f, 50f, { "%.0f".format(it) }, "wX", onLive = { set(w.copy(cx = it / 100), true) }) { set(w.copy(cx = it / 100)) }
            PropSlider(stringResource(R.string.colortools_center_y), w.cy * 100, 0f, 100f, 50f, { "%.0f".format(it) }, "wY", onLive = { set(w.copy(cy = it / 100), true) }) { set(w.copy(cy = it / 100)) }
            PropSlider(stringResource(R.string.colortools_width), w.rx * 100, 1f, 100f, 30f, { "%.0f".format(it) }, "wRx", onLive = { set(w.copy(rx = it / 100), true) }) { set(w.copy(rx = it / 100)) }
            PropSlider(stringResource(R.string.colortools_height), w.ry * 100, 1f, 100f, 30f, { "%.0f".format(it) }, "wRy", onLive = { set(w.copy(ry = it / 100), true) }) { set(w.copy(ry = it / 100)) }
            PropSlider(stringResource(R.string.colortools_softness), w.soft * 100, 0f, 100f, 30f, { "%.0f".format(it) }, "wSoft", onLive = { set(w.copy(soft = it / 100), true) }) { set(w.copy(soft = it / 100)) }
            PropSlider(stringResource(R.string.colortools_rotation), w.angle, -180f, 180f, 0f, { "%.0f°".format(it) }, "wAngle", onLive = { set(w.copy(angle = it), true) }) { set(w.copy(angle = it)) }
            // effect in the window: blur or pixelate (e.g. hiding a tracked face or sign)
            val fx = when { n.pixelate > 0f -> 2; n.blur > 0f -> 1; else -> 0 }
            Text(stringResource(R.string.colortools_window_effect), color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            ConnectedToggleGroup(listOf(stringResource(R.string.colortools_none), stringResource(R.string.colortools_blur), stringResource(R.string.colortools_pixelate)), fx, { i ->
                onCommit(when (i) { 1 -> n.copy(blur = n.blur.takeIf { it > 0f } ?: n.pixelate.takeIf { it > 0f } ?: 0.5f, pixelate = 0f)
                    2 -> n.copy(pixelate = n.pixelate.takeIf { it > 0f } ?: n.blur.takeIf { it > 0f } ?: 0.5f, blur = 0f)
                    else -> n.copy(blur = 0f, pixelate = 0f) })
            }, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), tag = { "wfx:$it" })
            if (fx == 1) PropSlider(stringResource(R.string.colortools_strength), n.blur * 100, 1f, 100f, 50f, { "%.0f".format(it) }, "wBlur", onLive = { onLive(n.copy(blur = it / 100)) }) { onCommit(n.copy(blur = it / 100)) }
            if (fx == 2) PropSlider(stringResource(R.string.colortools_cell_size), n.pixelate * 100, 1f, 100f, 50f, { "%.0f".format(it) }, "wPix", onLive = { onLive(n.copy(pixelate = it / 100)) }) { onCommit(n.copy(pixelate = it / 100)) }
            // tracking: the window follows the object through the clip (from the frame at the playhead, in both directions)
            val busy = TrackService.target == (clip to n.id)
            val progress = TrackService.progress
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    PillButton(stringResource(R.string.colortools_cancel), { TrackService.cancel() }, Modifier.testTag("win:trackCancel"), icon = Ic.Close, filled = false)
                } else {
                    PillButton(if (w.track != null) stringResource(R.string.colortools_retrack) else stringResource(R.string.colortools_track_object), {
                        colorClip?.let { TrackService.start(ctx, gs, it, n.id, playhead(), seek) }
                    }, Modifier.testTag("win:track"), icon = Ic.Target)
                    if (w.track != null) PillButton(stringResource(R.string.colortools_remove_tracking), { TrackService.clouds.remove(clip to n.id); gs.correctTrack = false; set(w.copy(track = null)) }, Modifier.testTag("win:trackClear"), icon = Ic.Delete, filled = false)
                }
            }
            // fix a track that drifted: drag the window into place where it went wrong, then continue tracking from there
            if (w.track != null && !busy) {
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip(stringResource(R.string.colortools_correct_from_here), gs.correctTrack, { gs.correctTrack = !gs.correctTrack }, Modifier.testTag("win:correct"))
                    ToggleChip(stringResource(R.string.colortools_continue_from_here), false, {
                        gs.correctTrack = false
                        colorClip?.let { TrackService.start(ctx, gs, it, n.id, playhead(), seek, continueFrom = true) }
                    }, Modifier.testTag("win:continue"), icon = Ic.Target)
                }
                if (gs.correctTrack) Text(stringResource(R.string.colortools_correct_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (busy && progress != null) {
                androidx.compose.material3.LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = K.Primary)
                Text(stringResource(R.string.colortools_tracking_progress, progress * 100), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            TrackService.message?.takeIf { !busy }?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("win:trackMsg")) }
            w.track?.let { t ->
                PropSlider(stringResource(R.string.colortools_smoothing), t.smooth * 100, 0f, 100f, 30f, { "%.0f".format(it) }, "wSmooth",
                    onLive = { set(w.copy(track = t.withSmooth(it / 100)), true) }) { set(w.copy(track = t.withSmooth(it / 100))) }
            }
        }
    }
}

/**
 * On top of the program image on the color page: the window you drag/pinch, the dropper (tap = pick a color) and
 * the comparison with a still (split screen, drag the line).
 */
@Composable
fun GradeOverlay(gs: GradeState, modifier: Modifier = Modifier, srcOf: (Long) -> Double? = { null }) {
    val scope = rememberCoroutineScope()
    if (!gs.active) return
    val cmp = gs.compare
    if (cmp != null) {
        val img by produceState<ImageBitmap?>(null, cmp.id) { value = withContext(Dispatchers.IO) { StillStore.image(cmp) } }
        Box(
            modifier.fillMaxSize().clipToBounds().testTag("compare")
                .pointerInput(cmp.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        gs.wipe = (down.position.x / size.width).coerceIn(0f, 1f)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            gs.wipe = (ch.position.x / size.width).coerceIn(0f, 1f)
                            ch.consume()
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val i = img ?: return@Canvas
                val x = size.width * gs.wipe
                clipRect(right = x) {
                    val f = min(size.width / i.width, size.height / i.height)
                    val iw = (i.width * f).roundToInt(); val ih = (i.height * f).roundToInt()
                    drawRect(Color.Black, size = Size(x, size.height))
                    drawImage(i, dstOffset = IntOffset(((size.width - iw) / 2).roundToInt(), ((size.height - ih) / 2).roundToInt()), dstSize = IntSize(iw, ih))
                }
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(Color.White, 10.dp.toPx(), Offset(x, size.height / 2))
            }
            Text(stringResource(R.string.colortools_still), color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            Text(stringResource(R.string.colortools_now), color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
    val pick = gs.pick
    if (pick != null) {
        Box(
            modifier.fillMaxSize().border(2.dp, K.Primary).testTag("pickOverlay").pointerInput(pick) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val x = (down.position.x / size.width).coerceIn(0f, 1f)
                    val y = (down.position.y / size.height).coerceIn(0f, 1f)
                    gs.pick = null
                    pick(x, y)
                }
            }
        ) {
            Text(gs.pickHint, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        return
    }
    val we = gs.windowEdit ?: return
    val node = gs.shown(we.first)?.nodes?.firstOrNull { it.id == we.second } ?: return
    if (!node.win.on) return
    // tracked window: drawn where it is at the playhead (drags move the base position); during tracking
    // along what has been tracked so far, with the points the tracker follows
    val src = srcOf(we.first)
    val partial = TrackService.partial?.takeIf { TrackService.target == we }
    val pBase = TrackService.partialBase
    val win = (if (partial != null && pBase != null) node.win.copy(cx = pBase.cx, cy = pBase.cy, rx = pBase.rx, ry = pBase.ry, track = partial) else node.win).at(src)
    val cloud = src?.let { t -> TrackService.clouds[we]?.at(t) }
    Box(
        modifier.fillMaxSize().testTag("windowOverlay").pointerInput(we) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var cur = gs.shown(we.first)?.nodes?.firstOrNull { it.id == we.second } ?: return@awaitEachGesture
                var changed = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.none { it.pressed }) break
                    // stabilized clip: the window is drawn in the moved/rotated/zoomed image – the drag is mapped
                    // back to source image coordinates so the window follows the finger
                    val pan = srcOf(we.first)?.let { t -> gs.grades[we.first]?.stab?.let { st ->
                        runCatching { st.transform(t, size.width.toFloat(), size.height.toFloat()).inverse() }.getOrNull()
                            ?.takeIf { it.a.isFinite() && it.d.isFinite() }
                            ?.let { inv -> val v = ev.calculatePan(); Offset(inv.a * v.x + inv.b * v.y, inv.c * v.x + inv.d * v.y) }
                    } } ?: ev.calculatePan()
                    val zoom = ev.calculateZoom()
                    val turn = ev.calculateRotation()
                    if (pan != Offset.Zero || zoom != 1f || turn != 0f) {
                        val w0 = cur.win
                        val t = w0.track
                        val src = srcOf(we.first)
                        cur = cur.copy(win = if (gs.correctTrack && t != null && src != null) {
                            // correct the tracking: move the window from this frame onward
                            w0.copy(track = t.shiftFrom(src, pan.x / size.width, pan.y / size.height))
                        } else w0.copy(
                            cx = (w0.cx + pan.x / size.width).coerceIn(-0.5f, 1.5f),
                            cy = (w0.cy + pan.y / size.height).coerceIn(-0.5f, 1.5f),
                            rx = (w0.rx * zoom).coerceIn(0.01f, 1.5f),
                            ry = (w0.ry * zoom).coerceIn(0.01f, 1.5f),
                            angle = normAngle(w0.angle + turn),
                        ))
                        gs.liveNode(we.first, cur)
                        changed = true
                    }
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                if (changed) { gs.commitNode(we.first, cur); scope.launch { gs.endLive() } }
            }
        }
    ) {
        // stabilized clip: the window and points move with the image
        val stab = src?.let { t -> gs.grades[we.first]?.stab?.let { st -> { w: Float, h: Float -> st.transform(t, w, h) } } }
        Canvas(Modifier.fillMaxSize()) {
          if (stab != null) {
              val a = stab(size.width, size.height)
              drawContext.canvas.nativeCanvas.concat(android.graphics.Matrix().apply {
                  // around the image center
                  setValues(floatArrayOf(a.a, a.b, a.tx + size.width / 2 - a.a * size.width / 2 - a.b * size.height / 2,
                      a.c, a.d, a.ty + size.height / 2 - a.c * size.width / 2 - a.d * size.height / 2, 0f, 0f, 1f))
              })
          }
          val c = Offset(win.cx * size.width, win.cy * size.height)
          // rotated window: drawn rotated around its center (the points below are drawn without rotation)
          rotate(win.angle, c) {
            val sz = Size(win.rx * 2 * size.width, win.ry * 2 * size.height)
            val tl = Offset(c.x - sz.width / 2, c.y - sz.height / 2)
            val st = Stroke(2.dp.toPx())
            val inner = (1f - win.soft * 0.5f).coerceAtLeast(0.02f)
            val outer = 1f + win.soft * 0.5f
            if (win.shape == 2) {
                drawRect(Color.White, tl, sz, style = st)
                drawRect(Color(0x88FFFFFF), Offset(c.x - sz.width * inner / 2, c.y - sz.height * inner / 2), Size(sz.width * inner, sz.height * inner), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                drawRect(Color(0x88FFFFFF), Offset(c.x - sz.width * outer / 2, c.y - sz.height * outer / 2), Size(sz.width * outer, sz.height * outer), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            } else {
                drawOval(Color.White, tl, sz, style = st)
                drawOval(Color(0x88FFFFFF), Offset(c.x - sz.width * inner / 2, c.y - sz.height * inner / 2), Size(sz.width * inner, sz.height * inner), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                drawOval(Color(0x88FFFFFF), Offset(c.x - sz.width * outer / 2, c.y - sz.height * outer / 2), Size(sz.width * outer, sz.height * outer), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            drawCircle(Color.White, 5.dp.toPx(), c)
            // direction marker: shows the rotation
            if (win.angle != 0f) drawLine(Color.White, c, Offset(c.x, c.y - sz.height / 2), 1.5.dp.toPx())
          }
            // point cloud: a small plus sign per point (yellow when tracking is uncertain), with a dark outline
            cloud?.pts?.let { p ->
                val col = if (cloud.q < 0.35f) Color(0xFFFFC857) else Color(0xFF7DFFB0)
                val a = 3.5.dp.toPx()
                val w1 = 1.5.dp.toPx()
                for (i in 0 until p.size / 2) {
                    val x = p[2 * i] * size.width; val y = p[2 * i + 1] * size.height
                    drawLine(Color(0xAA000000), Offset(x - a - 1, y), Offset(x + a + 1, y), w1 + 2f)
                    drawLine(Color(0xAA000000), Offset(x, y - a - 1), Offset(x, y + a + 1), w1 + 2f)
                    drawLine(col, Offset(x - a, y), Offset(x + a, y), w1)
                    drawLine(col, Offset(x, y - a), Offset(x, y + a), w1)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ stills gallery

/** A saved still: the graded image and the grade that produced it (like Resolve's gallery). */
data class Still(val id: String, val label: String, val time: Long, val grade: ClipGrade)

/** The gallery, saved in the app's files (shared by all projects). */
object StillStore {
    val stills = mutableStateListOf<Still>()
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "stills").apply { mkdirs() }
        load()
    }

    /** For tests: a separate folder. */
    fun initAt(d: File) { dir = d.apply { mkdirs() }; stills.clear(); load() }

    private fun index() = File(dir, "index.json")

    private fun load() {
        stills.clear()
        val a = runCatching { JSONArray(index().readText()) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val s = Still(o.getString("id"), o.optString("label"), o.optLong("time"), ClipGrade.fromJson(o.optJSONObject("grade")))
            if (File(dir, s.id + ".jpg").exists()) stills += s
        }
    }

    private fun save() {
        val a = JSONArray()
        stills.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label).put("time", it.time).put("grade", it.grade.toJson())) }
        index().writeText(a.toString())
    }

    /** Saves the image (on a background thread) and puts it first in the gallery (on the caller's thread). */
    suspend fun add(img: Bitmap, label: String, grade: ClipGrade): Still {
        val id = "still_" + System.currentTimeMillis() + "_" + (0..999).random()
        val d = dir ?: error("gallery not initialized")
        withContext(Dispatchers.IO) { File(d, "$id.jpg").outputStream().use { img.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        val s = Still(id, label, System.currentTimeMillis(), grade)
        stills.add(0, s)
        save()
        return s
    }

    fun delete(s: Still) {
        File(dir, s.id + ".jpg").delete()
        stills.removeAll { it.id == s.id }
        save()
    }

    fun image(s: Still): ImageBitmap? = runCatching { BitmapFactory.decodeFile(File(dir, s.id + ".jpg").absolutePath)?.asImageBitmap() }.getOrNull()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StillsRow(gs: GradeState, onGrab: () -> Unit, onMenu: (Still) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(K.Tonal).combinedClickable(onClick = onGrab).testTag("grabStill"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            androidx.compose.material3.Icon(Ic.Still, null, tint = K.PrimarySoft, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.colortools_grab_still), color = K.PrimarySoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        for (s in StillStore.stills) {
            val img by produceState<ImageBitmap?>(null, s.id) { value = withContext(Dispatchers.IO) { StillStore.image(s) } }
            val on = gs.compare?.id == s.id
            Box(
                Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(if (on) 20.dp else 16.dp)).background(K.SurfaceHighest)
                    .border(if (on) 3.dp else 0.dp, if (on) K.Primary else Color.Transparent, RoundedCornerShape(if (on) 20.dp else 16.dp))
                    .combinedClickable(onClick = { gs.compare = if (on) null else s }, onLongClick = { onMenu(s) })
                    .testTag("still:${s.id}"),
            ) {
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (on) Text(stringResource(R.string.colortools_compare), color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ program image (for scopes)

/**
 * Grabs the program image as actually displayed (the player's video surface, with grade and look from the GPU) via PixelCopy.
 * null if no video surface is found or the copy fails.
 */
suspend fun captureProgram(root: android.view.View, width: Int = 256): Bitmap? {
    val sv = findSurfaceView(root) ?: return null
    if (sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) return null
    val h = (width.toFloat() * sv.height / sv.width).roundToInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        runCatching {
            android.view.PixelCopy.request(sv, bmp, { r -> if (cont.isActive) cont.resumeWith(Result.success(if (r == android.view.PixelCopy.SUCCESS) bmp else null)) },
                android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
    }
}

private fun findSurfaceView(v: android.view.View): android.view.SurfaceView? {
    if (v is android.view.SurfaceView && v.isShown) return v
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findSurfaceView(v.getChildAt(i))?.let { return it }
    return null
}

/** Angle in degrees within −180..180. */
internal fun normAngle(a: Float): Float {
    var x = a % 360f
    if (x > 180f) x -= 360f
    if (x < -180f) x += 360f
    return x
}

/**
 * Clip stabilization (like Resolve's stabilizer on the color page): the whole image's motion is tracked from the clip's
 * start to end with the same tracker as the windows, then the path is smoothed during playback.
 */
object StabService {
    var progress by androidx.compose.runtime.mutableStateOf<Float?>(null)
        private set
    var target by androidx.compose.runtime.mutableStateOf<Long?>(null)
        private set
    var message by androidx.compose.runtime.mutableStateOf<String?>(null)
    private val scope = kotlinx.coroutines.MainScope()
    private var job: kotlinx.coroutines.Job? = null

    fun start(ctx: Context, gs: GradeState, c: ColorClip, strength: Float, mode: Int = 0, maxZoom: Float = 1.2f) {
        val uri = c.uri ?: run { message = Str.t(R.string.colortools_clip_file_missing); return }
        cancel()
        val lo = c.srcIn
        val hi = c.srcIn + (c.end - c.start) * kotlin.math.abs(c.speed)
        target = c.id
        progress = 0f
        message = null
        job = scope.launch {
            try {
                val t0 = System.nanoTime()
                // whole-image motion frame by frame (new points in every frame, across the whole image)
                val path = se.gendut.klipp.engine.WindowTracker.cameraPath(ctx, uri, lo, hi) { p -> scope.launch { progress = p } }
                val st = se.gendut.klipp.engine.Stabilization(path.withSmooth(0f), strength, mode, maxZoom)
                // the path is solved (and the zoom computed) here, not when the first frame is drawn
                val aspect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { aspectOf(ctx, uri) } ?: (16f / 9f)
                val z = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { st.zoom(aspect) }
                gs.set(c.id, gs.of(c.id).copy(stab = st))
                message = Str.t(R.string.colortools_stabilized, path.end - path.t0, (System.nanoTime() - t0) / 1e9, (z - 1f) * 100f) +
                    (if (z >= st.maxZoom - 0.001f && st.maxZoom > 1f) " " + Str.t(R.string.colortools_stab_zoom_cap) else "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = Str.t(R.string.colortools_stab_cancelled)
                throw e
            } catch (e: Throwable) {
                message = Str.t(R.string.colortools_stab_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                progress = null
                target = null
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }

    /** Image aspect in display orientation (width/height). */
    internal fun aspectOf(ctx: Context, uri: android.net.Uri): Float? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloat()
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloat()
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0f || h <= 0f) null else if (rot % 180 != 0) h / w else w / h
        } finally { r.release() }
    }.getOrNull()
}

/** The card on the color page: stabilize the clip, strength, remove. */
@Composable
fun StabilizeCard(gs: GradeState, clip: ColorClip) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val haptics = rememberHaptics()
    val st = gs.of(clip.id).stab
    val busy = StabService.target == clip.id
    PanelCard(Modifier.padding(top = 12.dp), title = stringResource(R.string.colortools_stabilization)) {
        if (st == null && !busy) {
            Text(stringResource(R.string.colortools_stab_intro),
                color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        }
        val p = StabService.progress
        if (busy && p != null) {
            androidx.compose.material3.LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), color = K.Primary)
            PillButton(stringResource(R.string.colortools_cancel), { StabService.cancel() }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp).testTag("stab:cancel"), icon = Ic.Close, filled = false)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (st == null) stringResource(R.string.colortools_stabilize_clip) else stringResource(R.string.colortools_restabilize), { haptics.confirm(); StabService.start(ctx, gs, clip, st?.strength ?: 0.6f, st?.mode ?: 0, st?.maxZoom ?: 1.2f) },
                    Modifier.testTag("stab:start"), icon = Ic.Target)
                if (st != null) PillButton(stringResource(R.string.colortools_remove), { haptics.tick(); gs.set(clip.id, gs.of(clip.id).copy(stab = null)) }, Modifier.testTag("stab:clear"), icon = Ic.Delete, filled = false)
            }
        }
        if (st != null && !busy) {
            // the new path is solved on a background thread (with the video's aspect) before it is committed – not at the first
            // frame on the main or graphics thread; only the latest change is committed
            val solve = rememberCoroutineScope()
            val job = androidx.compose.runtime.remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
            fun apply(ns: se.gendut.klipp.engine.Stabilization) {
                job[0]?.cancel()
                job[0] = solve.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val a = clip.uri?.let { StabService.aspectOf(ctx, it) } ?: (16f / 9f)
                        ns.zoom(a)
                    }
                    gs.set(clip.id, gs.of(clip.id).copy(stab = ns))
                }
            }
            ConnectedToggleGroup(listOf(stringResource(R.string.colortools_smooth_camera), stringResource(R.string.colortools_tripod)), st.mode, { haptics.tick(); apply(st.withMode(it)) },
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp), tag = { "stab:mode$it" })
            PropSlider(if (st.mode == 1) stringResource(R.string.colortools_softness_at_cap) else stringResource(R.string.colortools_strength), st.strength * 100, 0f, 100f, 60f, { "%.0f".format(it) }, "stabStrength", lane = false,
                onLive = {}) { apply(st.withStrength(it / 100)) }
            PropSlider(stringResource(R.string.colortools_max_zoom), (st.maxZoom - 1f) * 100, 0f, 40f, 20f, { "%.0f %%".format(it) }, "stabZoom", lane = false,
                onLive = {}) { apply(st.withMaxZoom(1f + it / 100)) }
        }
        StabService.message?.takeIf { !busy }?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp).testTag("stab:msg")) }
    }
}

