package io.github.shohei0205.yamamuki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** 現在地・方位への追従と手動操作を切り替える。 */
@Composable
fun MapModeButton(manual: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = modifier.size(56.dp).semantics {
            stateDescription = if (manual) "手動位置モード" else "ヘディングアップモード"
            contentDescription = if (manual) "現在地に戻り、進行方向を上にする" else "北を上にして手動位置モードにする"
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(28.dp)) {
                val color = if (!enabled) Color.LightGray else if (manual) Color(0xFF5F6368) else Color(0xFF1A73E8)
                val u = size.width / 28f
                if (manual) {
                    // 全周スコープ: 双眼鏡を中心に 360 度を見渡す円。
                    drawCircle(color, radius = 11 * u, style = Stroke(2.5f * u))
                    drawCircle(color, radius = 3 * u)
                } else {
                    // 扇状スコープ: 双眼鏡から向けた方向へ開く扇。
                    drawArc(color, startAngle = -120f, sweepAngle = 60f, useCenter = true,
                        topLeft = Offset(-6 * u, 4 * u), size = Size(40 * u, 40 * u))
                }
            }
        }
    }
}
