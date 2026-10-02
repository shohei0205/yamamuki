import SwiftUI

/// 右下のモード切替ボタンの絵。扇状スコープ(ヘディングアップ)は扇、全周スコープ(手動位置モード)は円。
struct ScopeModeIcon: View {
    let manual: Bool
    let color: Color

    var body: some View {
        Canvas { context, size in
            let u = size.width / 28
            if manual {
                // 全周スコープ: 双眼鏡を中心に 360 度を見渡す円。
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                context.stroke(Path(ellipseIn: CGRect(x: center.x - 11 * u, y: center.y - 11 * u, width: 22 * u, height: 22 * u)),
                    with: .color(color), lineWidth: 2.5 * u)
                context.fill(Path(ellipseIn: CGRect(x: center.x - 3 * u, y: center.y - 3 * u, width: 6 * u, height: 6 * u)),
                    with: .color(color))
            } else {
                // 扇状スコープ: 双眼鏡から向けた方向へ開く扇。
                var fan = Path()
                let apex = CGPoint(x: 14 * u, y: 24 * u)
                fan.move(to: apex)
                fan.addArc(center: apex, radius: 20 * u, startAngle: .degrees(-120), endAngle: .degrees(-60), clockwise: false)
                fan.closeSubpath()
                context.fill(fan, with: .color(color))
            }
        }
        .frame(width: 28, height: 28)
    }
}
