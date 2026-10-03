package io.github.shohei0205.yamamuki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 表示中の地図に対する北の方向。方位が未取得の間は針を出さない。
 * 左上の向きの表示や方位目盛りの下の札と同じく、淡い白の地に濃い色で描く。
 * 地図を端末の向きに合わせ続けている間([following])は、縁を濃い色の輪で囲む。
 * [tapFollows] はタップで端末の向きに合わせるか(false なら北を上にする)。
 */
@Composable
fun CompassIndicator(
    heading: Double?,
    following: Boolean,
    tapFollows: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.5f))
            .then(if (following) Modifier.border(2.dp, TapeInk, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button,
                onClickLabel = if (tapFollows) "端末の向きに合わせる" else "北を上にする", onClick = onClick)
            .semantics {
                contentDescription = when {
                    heading == null -> "コンパス：方位を取得中"
                    following -> "コンパス：端末の向きに合わせています。赤い針が北"
                    else -> "コンパス：赤い針が北"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (heading == null) {
            Text("—", color = TapeSubtle)
        } else {
            Box(Modifier.size(56.dp).rotate(-heading.toFloat()), contentAlignment = Alignment.TopCenter) {
                Text("N", color = Color(0xFFCC2525), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Canvas(Modifier.size(56.dp)) {
                    val x = center.x
                    val y = center.y + 3.dp.toPx()
                    val halfWidth = 5.dp.toPx()
                    val length = 14.dp.toPx()
                    fun needle(tip: Float) = Path().apply {
                        moveTo(x, tip)
                        lineTo(x - halfWidth, y)
                        lineTo(x + halfWidth, y)
                        close()
                    }
                    drawPath(needle(y - length), Color(0xFFCC2525))
                    drawPath(needle(y + length), TapeInk)
                }
            }
        }
    }
}
