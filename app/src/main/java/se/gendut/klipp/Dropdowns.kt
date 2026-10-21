package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One option in a dropdown menu. [key] becomes part of the test tag (default: the position in the list). */
class Opt<T>(val value: T, val label: String, val hint: String? = null, val enabled: Boolean = true, val key: String? = null)

/**
 * Dropdown: a heading, the selected value (with explanation) and a menu with all options. Options that aren't
 * available (e.g. a size the phone's encoder can't handle) are grayed out.
 *
 * Test tags: the field [tag], the options "[tag]:key".
 */
@Composable
fun <T> DropField(
    label: String?, options: List<Opt<T>>, selected: T, tag: String,
    modifier: Modifier = Modifier, onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val cur = options.firstOrNull { it.value == selected }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (label != null) Text(label, color = K.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        Box {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(K.SurfaceHighest)
                    .border(1.dp, if (open) K.Primary else K.OutlineVar, shape)
                    .clickable(onClickLabel = label) { haptics.tick(); open = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cur?.label ?: "–", color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    cur?.hint?.let { Text(it, color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Icon(Ic.ChevronDown, null, tint = K.OnSurfaceVar, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (open) 180f else 0f })
            }
            DropdownMenu(
                open, { open = false }, containerColor = K.SurfaceHighest,
                modifier = Modifier.heightIn(max = 420.dp).widthIn(min = 220.dp).testTag("$tag:menu"),
            ) {
                options.forEachIndexed { i, o ->
                    val on = o.value == selected
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(o.label, color = when { !o.enabled -> K.Outline; on -> K.PrimarySoft; else -> K.OnSurface },
                                    fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                                o.hint?.let { Text(it, color = if (o.enabled) K.OnSurfaceVar else K.Outline, fontSize = 11.sp) }
                            }
                        },
                        trailingIcon = if (on) ({ Icon(Ic.Check, null, tint = K.Primary, modifier = Modifier.size(18.dp)) }) else null,
                        enabled = o.enabled,
                        onClick = { open = false; haptics.tick(); onPick(o.value) },
                        modifier = Modifier.testTag("$tag:${o.key ?: i}"),
                    )
                }
            }
        }
    }
}
