package com.arshad.ayn.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.atWeight
import io.nayuki.qrcodegen.QrCode
import kotlin.math.floor

// `.qr` (px ÷ 2): a 200 dp white card, 14 dp corners; the code keeps the standard 4-module quiet
// zone inside it.
private val QrCard = 200.dp
private val QrShape = RoundedCornerShape(14.dp)
private const val QuietModules = 4
private val QrDark = Color(0xFF111111)

/**
 * [text] (an address to open on a phone: a sync sign-in, the source code) as a QR code on a white
 * card, [caption] (where it leads) under it (`m8-sync-tv` nextcloud, `b3-settings` source). The
 * code is read as [caption], so a sign-in address's token never reaches the semantics tree
 * (CMP-SEC-01).
 */
@Composable
internal fun AddressQr(text: String, caption: String, modifier: Modifier = Modifier) {
    val code = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier
                .size(QrCard)
                .semantics { contentDescription = "QR code for $caption" }
                .drawWithCache {
                    // Whole pixels per module keep the squares crisp; the rest centres the code.
                    val modules = code.size + 2 * QuietModules
                    val module = floor(size.minDimension / modules)
                    val start = Offset((size.width - module * code.size) / 2, (size.height - module * code.size) / 2)
                    val dark = Path()
                    for (y in 0 until code.size) {
                        for (x in 0 until code.size) {
                            if (code.getModule(x, y)) {
                                dark.addRect(Rect(Offset(start.x + x * module, start.y + y * module), Size(module, module)))
                            }
                        }
                    }
                    onDrawBehind {
                        drawInflated(QrShape, 0.dp, Color.White, 1f)
                        drawPath(dark, QrDark)
                    }
                },
        )
        Text(caption, style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 13.5.sp), color = KeColors.Ink2)
    }
}
