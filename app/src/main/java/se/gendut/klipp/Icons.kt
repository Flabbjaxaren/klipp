package se.gendut.klipp

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons (24×24, 2 px stroke) – the same shapes as in the design mockup. */
object Ic {
    private fun stroke(name: String, vararg paths: String): ImageVector = shifted(name, 0f, 0f, *paths)

    /**
     * A line icon moved by ([dx], [dy]) so its shape sits in the middle of the 24×24 box (buttons center the box, so an
     * off-center shape looks misplaced). IconCenteringTest measures every icon.
     */
    private fun shifted(name: String, dx: Float, dy: Float, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        b.addGroup(translationX = dx, translationY = dy)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.clearGroup()
        return b.build()
    }

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
            .build()

    val Back = stroke("back", "M15 18l-6-6 6-6")
    val Close = stroke("close", "M6 6l12 12M18 6L6 18")
    val Folder = stroke("folder", "M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z")
    val Lock = stroke("lock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 017 0v3")
    /** Film strip (spektrafilm). */
    val Film = stroke("film", "M5 3h14v18H5z", "M9 3v18", "M15 3v18", "M5 7.5h4", "M5 12h4", "M5 16.5h4", "M15 7.5h4", "M15 12h4", "M15 16.5h4")
    val Eye = stroke("eye", "M3 12s3.5-6 9-6 9 6 9 6-3.5 6-9 6-9-6-9-6z", "M12 9a3 3 0 110 6 3 3 0 010-6z")
    /** On/off (disabled clip). */
    val Power = shifted("power", 0f, 1.3f, "M12 3v8", "M7 6.5a7 7 0 1010 0")
    val Speaker = shifted("speaker", 1f, 0f, "M4 9h4l5-4v14l-5-4H4z", "M16.5 9a4 4 0 010 6")
    val SpeakerOff = shifted("speakeroff", -0.5f, 0f, "M4 9h4l5-4v14l-5-4H4z", "M16 9.5l5 5", "M21 9.5l-5 5")
    val Gear = stroke("gear",
        "M18.97 10.21L21.49 10.56L21.49 13.44L18.97 13.79L18.20 15.67L19.73 17.69L17.69 19.73L15.67 18.20L13.79 18.97L13.44 21.49L10.56 21.49L10.21 18.97L8.33 18.20L6.31 19.73L4.27 17.69L5.80 15.67L5.03 13.79L2.51 13.44L2.51 10.56L5.03 10.21L5.80 8.33L4.27 6.31L6.31 4.27L8.33 5.80L10.21 5.03L10.56 2.51L13.44 2.51L13.79 5.03L15.67 5.80L17.69 4.27L19.73 6.31L18.20 8.33z",
        "M12 9a3 3 0 110 6 3 3 0 010-6z")
    val EyeOff = stroke("eyeoff", "M3 12s3.5-6 9-6 9 6 9 6-3.5 6-9 6-9-6-9-6z", "M4 4l16 16")
    val Trim = stroke("trim", "M8 4v16", "M16 4v16", "M4 9l4 3-4 3", "M20 9l-4 3 4 3")
    val Undo = shifted("undo", 0f, 0.5f, "M9 14L4 9l5-5", "M4 9h11a5 5 0 010 10h-3")
    val Redo = shifted("redo", 0f, 0.5f, "M15 14l5-5-5-5", "M20 9H9a5 5 0 000 10h3")
    val Play = filled("play", "M8 5v14l11-7z")
    val Pause = filled("pause", "M7 5h4v14H7zM13 5h4v14h-4z")
    val Prev = stroke("prev", "M17 6l-7 6 7 6", "M7 6v12")
    val Next = stroke("next", "M7 6l7 6-7 6", "M17 6v12")
    val MarkIn = shifted("in", 4.5f, 0f, "M9 4H6v16h3")
    val MarkOut = shifted("out", -4.5f, 0f, "M15 4h3v16h-3")
    val Select = shifted("select", 0f, 1f, "M5 3l14 8-6 2-3 6z")
    val Ripple = shifted("ripple", -1f, 0f, "M8 4v16", "M14 8l4 4-4 4")
    val Razor = shifted("razor", 0.5f, 0f, "M6 3a3 3 0 1 0 0.01 0", "M6 15a3 3 0 1 0 0.01 0", "M8.5 7.5L20 18", "M8.5 16.5L20 6")
    val Plus = stroke("plus", "M12 5v14", "M5 12h14")
    val Media = stroke("media", "M6 5h12a3 3 0 013 3v8a3 3 0 01-3 3H6a3 3 0 01-3-3V8a3 3 0 013-3z", "M3 9h18")
    val Edit = stroke("edit", "M4 7h16", "M4 12h16", "M4 17h16")
    val Color_ = stroke("color", "M12 3a9 9 0 1 0 0.01 0", "M12 3v18")
    val Audio = stroke("audio", "M4 10v4", "M8 6v12", "M12 9v6", "M16 4v16", "M20 10v4")
    val Text = stroke("text", "M5 6V4h14v2", "M12 4v16", "M9 20h6")
    val Delete = stroke("delete", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Split = stroke("split", "M12 3v18", "M8 8l-4 4 4 4", "M16 8l4 4-4 4")
    val Marker = stroke("marker", "M6 3h12v13l-6 5-6-5z")
    val Magnet = shifted("magnet", 0f, 1.5f, "M5 3h4v8a3 3 0 006 0V3h4v8a7 7 0 01-14 0z", "M5 7h4", "M15 7h4")
    val Link = stroke("link", "M10 14a4 4 0 005.7 0l3-3a4 4 0 00-5.7-5.7l-1 1", "M14 10a4 4 0 00-5.7 0l-3 3a4 4 0 005.7 5.7l1-1")
    val Check = stroke("check", "M5 12l5 5 9-10")
    val Copy = stroke("copy", "M9 9h10a2 2 0 012 2v8a2 2 0 01-2 2H9a2 2 0 01-2-2v-8a2 2 0 012-2z", "M15 9V5a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2h2")
    val NextEdit = stroke("nextedit", "M4 6l7 6-7 6", "M12 6l7 6-7 6", "M20 5v14")
    val Speed = stroke("speed", "M12 5a8 8 0 1 0 0.01 0", "M12 13l4-4", "M10 3h4")
    val Sliders = stroke("sliders", "M4 7h10", "M18 7h2", "M16 5v4", "M4 17h4", "M12 17h8", "M10 15v4")
    val Fade = stroke("fade", "M3 19L12 5l9 14", "M7.5 12h9")
    val Subs = stroke("subs", "M4 6h16v12H4z", "M7 14h4", "M13 14h4", "M7 11h10")
    val Stopwatch = stroke("stopwatch", "M12 7a7 7 0 1 0 0.01 0", "M12 14V10", "M10 3h4", "M12 3v4")
    val KfPrev = shifted("kfprev", 0.5f, 0f, "M14 7l-5 5 5 5")
    val KfNext = shifted("kfnext", -0.5f, 0f, "M10 7l5 5-5 5")
    val Diamond = stroke("diamond", "M12 5l7 7-7 7-7-7z")
    /** The volume line: a level line with two keyframes. */
    val VolLine = shifted("volline", 0f, -0.5f, "M3 16h4l5-7h9", "M7 13.5l2.5 2.5-2.5 2.5-2.5-2.5z", "M12 6.5l2.5 2.5-2.5 2.5-2.5-2.5z")
    /** Pre-render (lightning bolt). */
    val Bolt = shifted("bolt", 0.5f, 0f, "M13 3L5 14h6l-1 7 8-11h-6z")
    val Center = stroke("center", "M12 3v18", "M6 9l-3 3 3 3", "M18 9l3 3-3 3", "M9 3h6")
    // color mode
    val Wheels = stroke("wheels", "M12 3a9 9 0 1 0 0.01 0", "M14.5 9.5a1.5 1.5 0 1 0 0.01 0")
    val Graph = stroke("graph", "M3 4v16h18", "M6 16c5 0 5-9 12-9")
    val Curves = stroke("curves", "M4 4h16v16H4z", "M4 20c6 0 4-12 16-16")
    val Cube = stroke("cube", "M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5", "M12 12v9")
    val Dropper = shifted("dropper", -0.58f, 0.58f, "M19.5 4.5a2.1 2.1 0 0 0-3 0L14 7l3 3 2.5-2.5a2.1 2.1 0 0 0 0-3z", "M15 8l-9.5 9.5L5 19l1.5-.5L16 9")
    val Window = stroke("window", "M12 4c4.4 0 8 3.6 8 8s-3.6 8-8 8-8-3.6-8-8 3.6-8 8-8z", "M12 8c2.2 0 4 1.8 4 4s-1.8 4-4 4-4-1.8-4-4 1.8-4 4-4z")
    val Paste = shifted("paste", 0f, -0.5f, "M9 4h6v3H9z", "M15 5h2a2 2 0 012 2v12a2 2 0 01-2 2H7a2 2 0 01-2-2V7a2 2 0 012-2h2")
    val Layers = shifted("layers", 0f, 0.5f, "M12 4l9 5-9 5-9-5z", "M3 14l9 5 9-5")
    val Reset = stroke("reset", "M4 12a8 8 0 1 0 2.3-5.7", "M4 4v4h4")
    val Target = stroke("target", "M12 4a8 8 0 1 0 0.01 0", "M12 2v4", "M12 18v4", "M2 12h4", "M18 12h4", "M12 11a1 1 0 1 0 0.01 0")
    val Waveform = stroke("waveform", "M3 5v14h18", "M6 14l3-6 3 8 3-10 3 6")
    val Parade = stroke("parade", "M3 5v14h18", "M7 9v7", "M12 7v9", "M17 11v5")
    val Vectorscope = stroke("vectorscope", "M12 3a9 9 0 1 0 0.01 0", "M12 7v10", "M7 12h10")
    val ChevronDown = stroke("chevdown", "M6 9l6 6 6-6")
    val Download = shifted("download", 0f, 0.5f, "M12 4v11", "M7 10l5 5 5-5", "M5 19h14")
    val Freeze = stroke("freeze", "M12 3v18", "M4.2 7.5l15.6 9", "M4.2 16.5l15.6-9", "M9.5 4.5L12 6l2.5-1.5", "M9.5 19.5L12 18l2.5 1.5")
    val Reverse = shifted("reverse", -1f, 0f, "M19 7l-5 5 5 5", "M12 7l-5 5 5 5")
    val Group = stroke("group", "M4 5h10v8H4z", "M10 11h10v8H10z")
    val Ungroup = stroke("ungroup", "M3 4h8v7H3z", "M13 13h8v7h-8z")
    /** Transcript (a page with lines of text). */
    val Transcript = shifted("transcript", -0.5f, 0f, "M6 3h9l4 4v14H6z", "M9 10h6", "M9 14h7", "M9 18h4")
    val Search = stroke("search", "M10.5 4a6.5 6.5 0 1 0 0.01 0", "M15.5 15.5L20 20")
    /** A tip (light bulb). */
    val Bulb = stroke("bulb", "M9 18h6", "M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3z")
    val Still = stroke("still", "M4 7h3l2-2h6l2 2h3v12H4z", "M12 10a3 3 0 1 0 0.01 0")
}
