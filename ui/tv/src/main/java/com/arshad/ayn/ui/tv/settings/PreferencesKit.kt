package com.arshad.ayn.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.settings.profileScope
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.drawKeyline
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.profiles.PlateSize
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan

// `.scope .mini` (px ÷ 2): a 16 dp plate.
private val ScopePlate = PlateSize(16.dp, 4.5.dp, 10.sp, 0.dp)
private val ScopeGap = 7.dp

/**
 * Whose settings a single-scope page holds (`.scope`): the profile's plate, "Arshad’s profile",
 * then " · each profile sets its own" fainter.
 */
@Composable
internal fun ScopeLine(me: Profile) {
    Row(
        modifier = Modifier.padding(start = RowInset, bottom = 6.dp).offset(y = (-2).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScopeGap),
    ) {
        ProfilePlate(me.name, me.tint, ScopePlate)
        Text(
            text = buildAnnotatedString {
                append(profileScope(me.name))
                withStyle(weightSpan(450, KeColors.Ink3)) { append(EACH_PROFILE) }
            },
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
            color = KeColors.Ink2,
        )
    }
}

/**
 * A group's heading on a page with both scopes (`.sub-h.scoped`): the profile's plate and name,
 * or the TV's icon and "Everyone on this Shield"; [text] when the group is someone's but not
 * their settings ("Set by Arshad, the owner"). The [first] sits right under the title.
 */
@Composable
internal fun ScopeHeading(me: Profile?, first: Boolean, text: String = me?.let { profileScope(it.name) } ?: EVERYONE_SCOPE) {
    Row(
        modifier = Modifier.padding(start = RowInset, top = if (first) 0.dp else 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScopeGap),
    ) {
        if (me != null) {
            ProfilePlate(me.name, me.tint, ScopePlate)
        } else {
            Icon(painterResource(R.drawable.ic_interface), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.size(15.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 12.5.sp),
            color = KeColors.Ink2,
        )
    }
}

/** An on/off setting: its switch at the row's end; OK flips it ([onSwitch] gets the new value). */
@Composable
internal fun SwitchRow(
    label: String,
    on: Boolean,
    onSwitch: (Boolean) -> Unit,
    focusKey: String,
    hint: String? = null,
    focusable: Boolean = true,
) {
    SettingRow(
        label = label,
        hint = hint,
        focusable = focusable,
        onClick = { onSwitch(!on) },
        modifier = Modifier.focusMemory(focusKey),
        control = { RowSwitch(on, it) },
    )
}

/** A page's small print (`.note`): [top] above it, at most 450 dp wide. */
@Composable
internal fun PaneNote(text: String, top: Dp = 6.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 1.45.em),
        color = KeColors.Ink3,
        modifier = Modifier.padding(start = RowInset, top = top).widthIn(max = 450.dp),
    )
}

private val SwatchEdge = Color.Black.copy(alpha = 0.25f)

/** A category's colour (`.srow .swatch`, 12 dp at the start of its row; [size] and [corner] for a sheet's title). */
@Composable
internal fun CategorySwatch(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp, corner: Dp = 4.dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .background(color, shape)
            .drawBehind { drawKeyline(shape, 0.5.dp, SwatchEdge, 1f) },
    )
}
