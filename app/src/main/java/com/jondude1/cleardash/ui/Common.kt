package com.jondude1.cleardash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** ATLAS-style glass: cut corners, a dim cyan hairline and bright corner brackets. */
private val CutCorners = CutCornerShape(10.dp)

private fun DrawScope.holoBrackets(color: Color, len: Float, width: Float, cut: Float) {
    val w = size.width; val h = size.height
    drawLine(color, Offset(cut, 0f), Offset(cut + len, 0f), width, StrokeCap.Square)
    drawLine(color, Offset(0f, cut), Offset(0f, cut + len), width, StrokeCap.Square)
    drawLine(color, Offset(w - cut - len, 0f), Offset(w - cut, 0f), width, StrokeCap.Square)
    drawLine(color, Offset(w, cut), Offset(w, cut + len), width, StrokeCap.Square)
    drawLine(color, Offset(cut, h), Offset(cut + len, h), width, StrokeCap.Square)
    drawLine(color, Offset(0f, h - cut - len), Offset(0f, h - cut), width, StrokeCap.Square)
    drawLine(color, Offset(w - cut - len, h), Offset(w - cut, h), width, StrokeCap.Square)
    drawLine(color, Offset(w, h - cut - len), Offset(w, h - cut), width, StrokeCap.Square)
}

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(CutCorners)
            .background(C.card)
            .border(1.dp, C.edge.copy(alpha = 0.45f), CutCorners)
            .drawWithContent {
                drawContent()
                holoBrackets(C.cyan, 14.dp.toPx(), 2.dp.toPx(), 10.dp.toPx() * 0.7f)
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 10.dp, height = 2.dp).background(C.cyan))
                Text(
                    title.uppercase(), color = C.cyan, fontFamily = HoloMono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp, modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (subtitle != null) Text(subtitle, color = C.text, fontFamily = HoloSans, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
        content()
    }
}

@Composable
fun StatusLine(label: String, value: String, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val col = when (ok) {
            true -> C.cyan
            false -> C.amber
            null -> C.muted.copy(alpha = 0.5f)
        }
        Box(
            Modifier
                .size(8.dp)
                .then(if (ok == true) Modifier.background(col) else Modifier.border(1.dp, col))
        )
        Spacer(Modifier.width(10.dp))
        Text(label.uppercase(), color = C.muted, fontFamily = HoloMono, fontSize = 12.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.text, fontFamily = HoloSans, fontSize = 14.sp)
    }
}

@Composable
fun Pill(text: String, color: Color = C.cyan, onClick: () -> Unit) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f))
            .drawWithContent {
                drawContent()
                holoBrackets(color, 6.dp.toPx(), 2.dp.toPx(), 0f)
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(text.uppercase(), color = color, fontFamily = HoloMono, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = C.muted, fontFamily = HoloSans, fontSize = 13.sp, lineHeight = 18.sp)
}
