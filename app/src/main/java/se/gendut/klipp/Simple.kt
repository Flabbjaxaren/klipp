package se.gendut.klipp

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.AfxSnap
import se.gendut.klipp.engine.AudioFxCatalog
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Drt
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.Wheel

/*
 * Simple and advanced mode (Settings → Mode, the edit page's ⋯ menu, and once at a fresh install's first start).
 *
 * The owner's rules: everything simple mode offers is a real function of advanced mode – nothing is built for simple
 * mode alone, and every simple control writes exactly the data its advanced counterpart writes (the shared writers below
 * and in ColorTools are used by both). Work made in advanced mode is never lost, hidden and dropped, or silently changed
 * by simple mode: what simple mode has no control for shows as a badge ([clipBadges], [trackBadge]) that offers to turn
 * it on or off (where advanced mode has that toggle), remove it (as advanced mode's own delete) or open it in advanced
 * mode, and a simple control that would clash with it is disabled with a one-line reason. Simple mode always uses the
 * magnetic timeline and snapping, without touching the advanced mode's own choices (see [EditorState.magnetic]).
 *
 * One mode state: [EditorState.simple] (kept in [AppPrefs.simpleMode]); the parts of the UI that don't get the editor
 * state read it through [LocalSimple].
 */

/** Simple mode, for the parts of the UI that don't get the editor state (export, settings, the shared editors). */
val LocalSimple = compositionLocalOf { false }

/** The sound effects simple mode has its own switches for (Sound FX: clean speech, remove noise). */
val SIMPLE_SOUND_FX = listOf("speech_enhance", "denoise")

/** What simple mode's color page reads and writes in a clip's grade – the same nodes and values as the advanced page. */
object SimpleGrade {
    /** OpenDRT as simple mode (and the advanced On chip) makes it: the defaults, only what it was shot in chosen. */
    fun plainDrt(d: Drt?): Boolean = d != null && d.copy(input = Drt().input) == Drt()

    /** OpenDRT turned on for footage shot in [input] (null = not known): the advanced LUT tab's On chip. */
    fun drtFor(input: String?): Drt = Drt().let { if (input != null) it.copy(input = input) else it }

    /** A node that holds nothing (an empty node added on the advanced page). */
    fun empty(n: GradeNode): Boolean = n.copy(label = "") == GradeNode(n.id)

    /** A node that only turns log footage into a picture: OpenDRT and nothing else. */
    fun drtOnly(n: GradeNode): Boolean = plainDrt(n.drt) && n.copy(label = "", drt = null) == GradeNode(n.id)

    /** A node with only what simple mode sets: exposure (the gain's master), contrast, warmth, saturation, a look (LUT), OpenDRT. */
    fun adjustOnly(n: GradeNode): Boolean =
        n.gain.x == 0f && n.gain.y == 0f && (n.drt == null || plainDrt(n.drt)) &&
            n.copy(label = "", gain = Wheel(), contrast = 1f, temp = 0f, sat = 1f, lut = null, lutMix = 1f, drt = null) == GradeNode(n.id)

    /** A node that only holds a look (a LUT and its strength): with log footage it comes after OpenDRT. */
    fun lookOnly(n: GradeNode): Boolean = n.lut != null && n.copy(label = "", lut = null, lutMix = 1f) == GradeNode(n.id)

    /** The node simple mode adjusts: the first that isn't OpenDRT alone. */
    fun adjustNode(g: ClipGrade): GradeNode? = g.nodes.firstOrNull { !drtOnly(it) } ?: g.nodes.firstOrNull()

    /**
     * The node that holds the look: with log footage a node of its own after OpenDRT (a look is made for a finished
     * picture – put on the log signal before the conversion it went wrong), otherwise the adjust node.
     */
    fun lookNode(g: ClipGrade): GradeNode? {
        val d = drtNode(g) ?: return adjustNode(g)
        val after = g.nodes.drop(g.nodes.indexOfFirst { it.id == d.id } + 1)
        return after.firstOrNull { lookOnly(it) || empty(it) } ?: adjustNode(g)?.takeIf { it.lut != null && it.id != d.id }
    }

    /**
     * A look picked (or its strength changed): with log footage into a node after OpenDRT (made there if missing; a
     * look still in the adjust node moves there), otherwise into the adjust node. null = no look (the look node goes).
     */
    fun withLook(g: ClipGrade, lut: String?, mix: Float? = null): ClipGrade {
        val d = drtNode(g)
        if (d == null || adjustNode(g)?.id == d.id) return adjust(g) { n -> lutPicked(n, lut).let { if (mix != null && lut != null) it.copy(lutMix = mix) else it } }
        val a = adjustNode(g)
        // the look node after OpenDRT, if there is one
        val after = g.nodes.drop(g.nodes.indexOfFirst { it.id == d.id } + 1)
        val l = after.firstOrNull { lookOnly(it) || empty(it) }
        var nodes = g.nodes.map { if (a != null && it.id == a.id && it.lut != null) it.copy(lut = null, lutMix = 1f) else it }
        val oldMix = l?.lutMix ?: a?.takeIf { it.lut != null }?.lutMix
        // no look: the look node stays, empty (it changes nothing, and HLG keeps its full range – OpenDRT is last)
        if (lut == null) return g.copy(nodes = nodes.map { if (l != null && it.id == l.id) it.copy(lut = null, lutMix = 1f) else it })
        // the strength: as given, else the look's own (a look picked anew keeps it – the advanced LUT tab's rule), else full
        val look = (l ?: GradeNode((g.nodes.maxOfOrNull { it.id } ?: 0) + 1, "Look")).copy(lut = lut, lutMix = mix ?: oldMix ?: 1f)
        nodes = if (l != null) nodes.map { if (it.id == l.id) look else it } else nodes + look
        return g.copy(nodes = nodes)
    }

    /** The node with OpenDRT (log footage), or null. */
    fun drtNode(g: ClipGrade): GradeNode? = g.nodes.firstOrNull { it.drt != null }

    /** Simple mode can show and change everything in the clip's color (null = never graded). */
    fun fits(g: ClipGrade?): Boolean {
        if (g == null) return true
        val a = adjustNode(g) ?: return false
        if (!adjustOnly(a) || g.nodes.count { it.drt != null } > 1) return false
        val d = drtNode(g)
        val afterDrt = if (d == null) emptySet() else g.nodes.drop(g.nodes.indexOfFirst { it.id == d.id } + 1).map { it.id }.toSet()
        return g.nodes.all { it.id == a.id || empty(it) || drtOnly(it) || (it.id in afterDrt && lookOnly(it)) } &&
            g.nodes.count { lookOnly(it) && it.id in afterDrt } <= 1
    }

    /**
     * *All clips* on simple mode's filter: what simple mode sets on [from] – the look and its strength, exposure,
     * contrast, warmth, saturation – onto [to]. [to] keeps the rest of its own: its log footage (OpenDRT, with the look
     * after it) and what Edit › Picture set on it.
     */
    fun carry(from: ClipGrade, to: ClipGrade): ClipGrade {
        val a = adjustNode(from) ?: return to
        // (the look in the adjust node itself – also one that holds OpenDRT, as a raw clip's developed node does)
        val lk = lookNode(from) ?: a
        val adjusted = adjust(to) { it.copy(gain = it.gain.copy(master = a.gain.master), contrast = a.contrast, temp = a.temp, sat = a.sat) }
        return withLook(adjusted, lk?.lut, lk?.lutMix)
    }

    /** [g] with its adjust node changed by [f]. */
    fun adjust(g: ClipGrade, f: (GradeNode) -> GradeNode): ClipGrade {
        val a = adjustNode(g) ?: return g
        return g.copy(nodes = g.nodes.map { if (it.id == a.id) f(it) else it })
    }

    /**
     * Log footage on: a node with OpenDRT at the end (as the advanced page's new node and its LUT tab's On chip – last,
     * so the clip can still be exported in HLG); off: that node goes (the advanced node menu's Delete), or OpenDRT in a
     * node that holds more is turned off (the LUT tab's Off chip).
     */
    fun withLog(g: ClipGrade, on: Boolean, input: String?): ClipGrade {
        val d = drtNode(g)
        if (on) {
            if (d != null) return g
            val id = (g.nodes.maxOfOrNull { it.id } ?: 0) + 1
            // OpenDRT, then an (empty) node for the look – a look already picked moves there, after the conversion
            val withDrt = g.copy(nodes = g.nodes + GradeNode(id, "OpenDRT", drt = drtFor(input)) + GradeNode(id + 1, "Look"))
            val a = adjustNode(g)
            return if (a?.lut != null) withLook(withDrt, a.lut, a.lutMix) else withDrt
        }
        if (d == null) return g
        // a look after the conversion moves back into the adjust node
        val look = lookNode(g)?.takeIf { (lookOnly(it) || empty(it)) && it.id != adjustNode(g)?.id }
        val g1 = if (look == null) g else g.copy(nodes = g.nodes.filter { it.id != look.id }.map { n ->
            if (n.id == adjustNode(g)?.id && look.lut != null) n.copy(lut = look.lut, lutMix = look.lutMix) else n })
        val d1 = drtNode(g1) ?: return g1
        return if (drtOnly(d1) && g1.nodes.size > 1) g1.copy(nodes = g1.nodes.filter { it.id != d1.id })
        else g1.copy(nodes = g1.nodes.map { if (it.id == d1.id) it.copy(drt = null) else it })
    }

    /** The log footage's input (what it was shot in): the advanced LUT tab's "Shot in" chips. */
    fun withInput(g: ClipGrade, input: String): ClipGrade =
        g.copy(nodes = g.nodes.map { n -> n.drt?.let { n.copy(drt = it.copy(input = input)) } ?: n })

    /** All nodes on, all off, or mixed (null) – advanced color's on/off is offered only when they agree. */
    fun allOn(g: ClipGrade): Boolean? = when {
        g.nodes.all { it.enabled } -> true
        g.nodes.none { it.enabled } -> false
        else -> null
    }

    /** Every node turned on or off (the advanced node menu's Enable / Disable, node by node). */
    fun setEnabled(g: ClipGrade, on: Boolean): ClipGrade = g.copy(nodes = g.nodes.map { it.copy(enabled = on) })
}

/** A LUT picked for a node: the advanced LUT tab's rule (a first LUT at full strength, a new one keeps the strength). */
fun lutPicked(n: GradeNode, name: String?): GradeNode = if (name == null) n.copy(lut = null) else n.copy(lut = name, lutMix = if (n.lut == null) 1f else n.lutMix)

/** What simple mode can't show, as a badge on the clip (or a track) and a row behind the dock's Advanced. */
enum class BadgeKind(@StringRes val label: Int, @StringRes val hint: Int) {
    COLOR(R.string.simple_badge_color, R.string.simple_badge_color_hint),
    KEYFRAMES(R.string.simple_badge_keyframes, R.string.simple_badge_keyframes_hint),
    MASK(R.string.simple_badge_mask, R.string.simple_badge_mask_hint),
    KEY(R.string.simple_badge_key, R.string.simple_badge_key_hint),
    BLEND(R.string.simple_badge_blend, R.string.simple_badge_blend_hint),
    MOTION_BLUR(R.string.simple_badge_motion_blur, R.string.simple_badge_motion_blur_hint),
    SOUND_FX(R.string.simple_badge_sound_fx, R.string.simple_badge_sound_fx_hint),
    FOLLOW(R.string.simple_badge_follow, R.string.simple_badge_follow_hint),
    PIN(R.string.simple_badge_pin, R.string.simple_badge_pin_hint),
    TRACK_FX(R.string.simple_badge_track_fx, R.string.simple_badge_track_fx_hint);

    val icon: ImageVector get() = when (this) {
        COLOR -> Ic.Color_
        KEYFRAMES -> Ic.Diamond
        MASK -> Ic.Window
        KEY -> Ic.Dropper
        BLEND -> Ic.Layers
        MOTION_BLUR -> Ic.Film
        SOUND_FX, TRACK_FX -> Ic.Audio
        FOLLOW -> Ic.Target
        PIN -> Ic.Grid
    }
}

/**
 * One badge: what it is, how many (keyed properties, effects), and [on] = whether it is on, where advanced mode has an
 * on/off for it (null = no such switch, or some on and some off).
 */
data class Badge(val kind: BadgeKind, val count: Int = 1, val on: Boolean? = null, val canToggle: Boolean = false)

/** The keyed properties simple mode counts (the clip's position, scale, rotation, opacity, volume). */
private val KEYED = listOf("position", "scale", "rotation", "opacity", "level")

/**
 * The badges of clip [c]: what was made for it in advanced mode that simple mode has no control for. [grade] = its
 * grade (null = none), [follows] / [pinned] = a title that follows a tracked object or is corner-pinned.
 */
fun clipBadges(c: Clip, extras: ClipExtras?, grade: ClipGrade?, follows: Boolean = false, pinned: Boolean = false): List<Badge> {
    val out = ArrayList<Badge>()
    val sound = c.kind == ClipKind.AUDIO || c.kind == ClipKind.MUSIC
    if ((c.kind == ClipKind.VIDEO || c.kind == ClipKind.ADJUST) && grade != null && !SimpleGrade.fits(grade)) {
        val on = SimpleGrade.allOn(grade)
        out += Badge(BadgeKind.COLOR, on = on, canToggle = on != null)
    }
    val keyed = extras?.keyed.orEmpty().filter { it in KEYED }
    if (keyed.isNotEmpty()) out += Badge(BadgeKind.KEYFRAMES, keyed.size)
    val layer = grade?.layer
    if (layer?.mask != null) out += Badge(BadgeKind.MASK)
    if (layer?.key != null) out += Badge(BadgeKind.KEY)
    if (!sound && c.kind != ClipKind.TITLE && (layer?.blend ?: se.gendut.klipp.engine.BlendMode.NORMAL) != se.gendut.klipp.engine.BlendMode.NORMAL ||
        c.kind == ClipKind.VIDEO && extras != null && extras.opacity != 100f && "opacity" !in keyed) out += Badge(BadgeKind.BLEND)
    if (grade?.mblur != null) out += Badge(BadgeKind.MOTION_BLUR)
    val fx = extras?.afx.orEmpty().filter { it.id !in SIMPLE_SOUND_FX }
    if (fx.isNotEmpty()) {
        val on = fxOn(fx)
        out += Badge(BadgeKind.SOUND_FX, fx.size, on, on != null)
    }
    if (follows) out += Badge(BadgeKind.FOLLOW)
    if (pinned) out += Badge(BadgeKind.PIN)
    return out
}

/** The badge of a track's own effects (the mixer's inserts), or null when it has none. */
fun trackBadge(t: TrackState): Badge? = t.afx.takeIf { it.isNotEmpty() }?.let { fxOn(it).let { on -> Badge(BadgeKind.TRACK_FX, it.size, on, on != null) } }

private fun fxOn(fx: List<AfxSnap>): Boolean? = when { fx.all { it.on } -> true; fx.none { it.on } -> false; else -> null }

/** The badges of a clip in the editor as it is now (its extras from the engine, its grade, a title's follow and pin). */
fun EditorState.badgesOf(c: Clip, gs: GradeState?): List<Badge> =
    clipBadges(c, clipExtras[c.id], gs?.grades?.get(c.id), gs?.follows?.containsKey(c.id) == true, gs?.pins?.containsKey(c.id) == true)

/** A badge's name as shown: "Keyframes (2)" when there are several. */
@Composable
fun badgeName(b: Badge): String = if (b.count > 1) stringResource(R.string.simple_badge_count, stringResource(b.kind.label), b.count) else stringResource(b.kind.label)

/**
 * What a badge's choices do – each the advanced mode's own function: on/off as its switch, remove as its delete, and
 * open switches to advanced mode with that tool open for the clip (or track).
 */
class BadgeActions(private val state: EditorState, private val gs: GradeState?) {
    /** Turns the clip's [b] on or off. */
    fun toggle(clip: Long, b: Badge) {
        val on = b.on ?: return
        when (b.kind) {
            BadgeKind.COLOR -> gs?.let { it.set(clip, SimpleGrade.setEnabled(it.of(clip), !on)) }
            BadgeKind.SOUND_FX -> state.clipExtras[clip]?.afx.orEmpty().filter { it.id !in SIMPLE_SOUND_FX && it.on == on }.forEach { state.props?.toggleAudioFx(clip, it.index) }
            else -> {}
        }
    }

    /** Removes the clip's [b], as advanced mode's own delete for it. */
    fun remove(clip: Long, b: Badge) {
        val g = gs
        when (b.kind) {
            BadgeKind.COLOR -> g?.let { it.set(clip, it.of(clip).colorReset()) }
            // the stopwatch off: the value at the playhead stays as a fixed value
            BadgeKind.KEYFRAMES -> state.clipExtras[clip]?.keyed.orEmpty().filter { it in KEYED }.forEach { state.props?.toggleAnimation(clip, it, state.playhead) }
            BadgeKind.MASK -> g?.let { layerOff(it, clip) { l -> l.copy(mask = null) } }
            BadgeKind.KEY -> g?.let { layerOff(it, clip) { l -> l.copy(key = null) } }
            BadgeKind.BLEND -> {
                g?.let { layerOff(it, clip) { l -> l.copy(blend = se.gendut.klipp.engine.BlendMode.NORMAL) } }
                if ((state.clipExtras[clip]?.opacity ?: 100f) != 100f) state.props?.setProps(clip, opacity = 100f)
            }
            BadgeKind.MOTION_BLUR -> g?.let { it.set(clip, it.of(clip).copy(mblur = null)) }
            // (from the last: the places of those before stay as they are)
            BadgeKind.SOUND_FX -> state.clipExtras[clip]?.afx.orEmpty().filter { it.id !in SIMPLE_SOUND_FX }.sortedByDescending { it.index }.forEach { state.props?.removeAudioFx(clip, it.index) }
            BadgeKind.FOLLOW -> g?.setFollow(clip, null)
            BadgeKind.PIN -> g?.setPin(clip, null)
            BadgeKind.TRACK_FX -> {}
        }
    }

    private fun layerOff(g: GradeState, clip: Long, f: (se.gendut.klipp.engine.LayerFx) -> se.gendut.klipp.engine.LayerFx) {
        val cg = g.of(clip)
        val l = cg.layer ?: return
        g.set(clip, cg.copy(layer = f(l).takeUnless { it.isNone }))
    }

    /** Advanced mode, with the tool for the clip's [b] open (the color page for its grade). */
    fun open(clip: Long, b: Badge) {
        val c = state.clips.firstOrNull { it.id == clip } ?: return
        state.switchMode(false)
        state.clearSelection()
        state.selected = clip
        if (state.playhead < c.start || state.playhead >= c.end) { state.playing = false; state.playhead = c.start }
        if (b.kind == BadgeKind.COLOR) { gs?.grid = false; state.onShowPage?.invoke(1); return }
        val keyed = state.clipExtras[clip]?.keyed.orEmpty()
        val sound = c.kind == ClipKind.AUDIO || c.kind == ClipKind.MUSIC
        val (group, tab) = when {
            c.kind == ClipKind.TITLE -> 0 to "titleTab:place"
            b.kind == BadgeKind.SOUND_FX -> 0 to "propTab:afx"
            sound -> 0 to "propTab:volume"
            b.kind == BadgeKind.MASK -> GROUP_PICTURE to "propTab:mask"
            b.kind == BadgeKind.KEY -> GROUP_PICTURE to "propTab:key"
            b.kind == BadgeKind.BLEND -> GROUP_PICTURE to "propTab:blend"
            b.kind == BadgeKind.MOTION_BLUR -> GROUP_PICTURE to "propTab:mblur"
            keyed.any { it == "position" || it == "scale" || it == "rotation" } -> GROUP_PICTURE to "propTab:motion"
            else -> GROUP_PICTURE to "propTab:blend"
        }
        state.onShowPage?.invoke(0)
        state.inspectGroup = group
        state.openTab = tab
        state.inspecting = true
    }

    /** Turns audio track [index]'s own effects on or off (the effects tab's power buttons). */
    fun toggleTrack(index: Int, t: TrackState) {
        val on = fxOn(t.afx) ?: return
        val ops = state.trackFxOps?.invoke(index) ?: return
        t.afx.filter { it.on == on }.forEach { ops.toggle(it.index) }
    }

    /** Removes audio track [index]'s own effects (the effects tab's remove, from the last). */
    fun removeTrack(index: Int, t: TrackState) {
        val ops = state.trackFxOps?.invoke(index) ?: return
        t.afx.sortedByDescending { it.index }.forEach { ops.remove(it.index) }
    }

    /** Advanced mode's audio page with track [index]'s effects open. */
    fun openTrack(index: Int) {
        state.switchMode(false)
        state.clearSelection()
        state.audioFxTrack = index
        state.openTab = "audioTab:effects"
        state.onShowPage?.invoke(2)
    }
}

/**
 * A badge's choices, in a dialog over the page: what it is (one line), then on/off (where advanced mode has it), Remove
 * and Open in advanced mode.
 */
@Composable
fun BadgeDialog(b: Badge, onToggle: (() -> Unit)?, onRemove: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(b.kind.icon, null, tint = K.OnSurface, modifier = Modifier.size(24.dp)) },
        title = { Text(badgeName(b)) },
        text = {
            Column(Modifier.testTag("badgeDialog")) {
                Text(stringResource(b.kind.hint), color = K.OnSurfaceVar, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                if (b.on == false) Text(stringResource(R.string.simple_badge_off), color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                // a mixed state is left to advanced mode: turning all on (or off) would lose which were which
                if (onToggle == null && b.on == null && (b.kind == BadgeKind.COLOR || b.kind == BadgeKind.SOUND_FX || b.kind == BadgeKind.TRACK_FX)) Text(stringResource(R.string.simple_badge_mixed), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                if (onToggle != null && b.on != null) BadgeChoice(stringResource(if (b.on) R.string.simple_badge_turn_off else R.string.simple_badge_turn_on), Ic.Power, "badge:toggle") { haptics.tick(); onToggle() }
                BadgeChoice(stringResource(R.string.simple_badge_remove), Ic.Delete, "badge:remove", danger = true) { haptics.heavy(); onRemove() }
                BadgeChoice(stringResource(R.string.simple_badge_open), Ic.Sliders, "badge:open") { haptics.press(); onOpen() }
                MessageHost(inline = true)
            }
        },
        confirmButton = { FlatTextButton(onClick = onDismiss, modifier = Modifier.testTag("badge:close")) { Text(stringResource(R.string.simple_badge_close)) } },
        containerColor = K.SurfaceHigh,
    )
}

@Composable
private fun BadgeChoice(label: String, icon: ImageVector, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) K.Error else K.OnSurface, modifier = Modifier.size(20.dp))
        Text(label, color = if (danger) K.Error else K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp))
    }
}

/**
 * The badges of clip [clip] in a dialog chain: the row of badges is in the dock ([BADGES][DockRow.BADGES]); the one
 * tapped opens here. [picked] = the badge open (null = none).
 */
@Composable
fun ClipBadgeDialog(state: EditorState, gs: GradeState?, clip: Long, b: Badge, onDismiss: () -> Unit) {
    val act = remember(state, gs) { BadgeActions(state, gs) }
    BadgeDialog(
        b,
        onToggle = if (b.canToggle) ({ act.toggle(clip, b); onDismiss() }) else null,
        onRemove = { act.remove(clip, b); onDismiss() },
        onOpen = { onDismiss(); act.open(clip, b) },
        onDismiss = onDismiss,
    )
}

/** A small mark on a clip in the timeline (simple mode): it holds something made in advanced mode. */
@Composable
fun BadgeMark(badges: List<Badge>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Box(
        modifier.size(16.dp).clip(CircleShape).background(K.SurfaceHighest.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) { Icon(if (badges.size == 1) badges[0].kind.icon else Ic.Sliders, null, tint = K.PrimarySoft, modifier = Modifier.size(10.dp)) }
}

/** A control that simple mode can't use for this clip: why, in one line (under the control). */
@Composable
fun DisabledReason(text: String, tag: String = "reason") {
    Text(text, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag(tag))
}

/**
 * A fresh install's first start: simple or advanced, as two cards; advanced asks next how the timeline works – tracks
 * or the magnetic timeline (the same setting as Settings → Experimental; simple mode always edits on the magnetic one).
 * Skipping keeps advanced on tracks (as for everyone who used the app before the modes); all of it can be changed any
 * time in Settings or the ⋯ menu.
 *
 * @param onPick the mode picked (true = simple) and, for advanced, whether the timeline is magnetic (null = unchanged)
 */
@Composable
fun ModeChooser(onPick: (Boolean, Boolean?) -> Unit, onSkip: () -> Unit) {
    var timeline by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (timeline) timeline = false else onSkip() }
    Box(
        Modifier.fillMaxSize().background(K.Bg).pointerInput(Unit) {}.windowInsetsPadding(WindowInsets.safeDrawing).testTag("modeAsk"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!timeline) {
                Text(stringResource(R.string.simple_choose_title), color = K.OnSurface, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text(stringResource(R.string.simple_choose_text), color = K.OnSurfaceVar, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
                ModeCard(stringResource(R.string.simple_mode_simple), stringResource(R.string.simple_mode_simple_hint), Ic.Bolt, "modeAsk:simple") { onPick(true, null) }
                ModeCard(stringResource(R.string.simple_mode_advanced), stringResource(R.string.simple_mode_advanced_hint), Ic.Sliders, "modeAsk:advanced") { timeline = true }
                FlatTextButton(onClick = onSkip, modifier = Modifier.padding(top = 4.dp).testTag("modeAsk:skip")) { Text(stringResource(R.string.simple_choose_skip)) }
            } else {
                Text(stringResource(R.string.simple_choose_timeline_title), color = K.OnSurface, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text(stringResource(R.string.simple_choose_timeline_text), color = K.OnSurfaceVar, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
                ModeCard(stringResource(R.string.simple_choose_tracks), stringResource(R.string.simple_choose_tracks_hint), Ic.Layers, "modeAsk:tracks") { onPick(false, false) }
                ModeCard(stringResource(R.string.magnetic_setting), stringResource(R.string.simple_choose_magnetic_hint), Ic.Storyline, "modeAsk:magnetic") { onPick(false, true) }
                FlatTextButton(onClick = { timeline = false }, modifier = Modifier.padding(top = 4.dp).testTag("modeAsk:back")) { Text(stringResource(R.string.app_back)) }
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, hint: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().pressPop(src, 0.96f).clip(RoundedCornerShape(28.dp)).background(K.SurfaceHigh)
            .clickable(interactionSource = src, indication = null, role = Role.Button) { haptics.confirm(); onClick() }
            .padding(horizontal = 18.dp, vertical = 20.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(K.PrimaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = K.PrimarySoft, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(hint, color = K.OnSurfaceVar, fontSize = 14.sp)
        }
    }
}

/** Settings' first row: simple or advanced (the same switch as the ⋯ menu's). */
@Composable
fun ModeRow(simple: Boolean, onMode: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("set:mode")) {
        Text(stringResource(R.string.simple_mode), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.simple_mode_setting_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        ConnectedToggleGroup(
            listOf(stringResource(R.string.simple_mode_simple), stringResource(R.string.simple_mode_advanced)), if (simple) 0 else 1,
            { onMode(it == 0) }, Modifier.fillMaxWidth(), tag = { if (it == 0) "mode:simple" else "mode:advanced" },
        )
    }
}

/**
 * Simple mode's sound effects for a clip: speech enhance and noise reduction as two switches (the same effects in the
 * clip's rack as advanced mode's, with their default settings; the rendering's progress as in the rack).
 */
@Composable
fun SimpleSoundFx(p: ClipProps, backend: PropsBackend) {
    val haptics = rememberHaptics()
    for (id in SIMPLE_SOUND_FX) {
        val fx = AudioFxCatalog.all.firstOrNull { it.id == id } ?: continue
        val on = simpleFxOn(p.afx, id)
        SwitchLine(fx.label, fx.blurb, on, "sfx:$id") { haptics.tick(); setSimpleFx(backend, p.id, p.afx, id, it) }
    }
    if (p.afx.any { it.on && it.id in se.gendut.klipp.engine.AudioRenders.RENDERED }) se.gendut.klipp.engine.AudioRenders.statusOf(p.id)?.let { RenderLine(it) }
}

/** A line with a name, what it does and a switch (the whole line switches). */
@Composable
fun SwitchLine(title: String, hint: String?, on: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = title) { onChange(!on) }.padding(horizontal = 18.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) K.OnSurface else K.Outline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        Spacer(Modifier.size(12.dp))
        androidx.compose.material3.Switch(on, onChange, enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = K.PrimarySoft, checkedTrackColor = K.PrimaryContainer))
    }
}

/** The simple sound effects' switch for a clip: is [id] (speech_enhance / denoise) on in its rack. */
fun simpleFxOn(afx: List<AfxSnap>, id: String): Boolean = afx.any { it.id == id && it.on }

/**
 * A simple sound switch turned: on = the effect added to the clip's rack (or the one there turned back on); off = removed
 * from the rack – unless its settings were changed in advanced mode, then only turned off (its power button), so they
 * are kept.
 */
fun setSimpleFx(backend: PropsBackend, clip: Long, afx: List<AfxSnap>, id: String, on: Boolean) {
    val have = afx.filter { it.id == id }
    if (on) {
        val off = have.firstOrNull { !it.on }
        if (have.none { it.on }) { if (off != null) backend.toggleAudioFx(clip, off.index) else backend.addAudioFx(clip, id) }
        return
    }
    val fx = AudioFxCatalog.all.firstOrNull { it.id == id }
    for (e in have.filter { it.on }.sortedByDescending { it.index }) {
        val tuned = fx != null && fx.params.any { p -> e.params[p.id]?.let { kotlin.math.abs(it - p.default) > 1e-6 } == true }
        if (tuned) backend.toggleAudioFx(clip, e.index) else backend.removeAudioFx(clip, e.index)
    }
}
