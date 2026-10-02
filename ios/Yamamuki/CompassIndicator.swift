import SwiftUI

/// 表示中の地図に対する北の方向。方位が未取得の間は針を出さない。
/// 左上の向きの表示や方位目盛りの下の札と同じく、淡い白の地に濃い色で描く。
/// 地図を端末の向きに合わせ続けている間([following])は、縁を濃い色の輪で囲む。
struct CompassIndicator: View {
    let heading: Double?
    let following: Bool

    var body: some View {
        ZStack {
            Circle().fill(Color.white.opacity(0.5))
            if following {
                Circle().strokeBorder(tapeInk, lineWidth: 2)
            }
            if let heading {
                ZStack(alignment: .top) {
                    Text("N")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color(red: 0.8, green: 0.145, blue: 0.145))
                    Canvas { context, size in
                        let x = size.width / 2
                        let y = size.height / 2 + 3
                        func needle(_ tip: CGFloat) -> Path {
                            Path { path in
                                path.move(to: CGPoint(x: x, y: tip))
                                path.addLine(to: CGPoint(x: x - 5, y: y))
                                path.addLine(to: CGPoint(x: x + 5, y: y))
                                path.closeSubpath()
                            }
                        }
                        context.fill(needle(y - 14), with: .color(Color(red: 0.8, green: 0.145, blue: 0.145)))
                        context.fill(needle(y + 14), with: .color(tapeInk))
                    }
                }
                .rotationEffect(.degrees(-heading))
            } else {
                Text("—").foregroundStyle(tapeSubtle)
            }
        }
        .frame(width: 56, height: 56)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(heading == nil ? "コンパス：方位を取得中"
            : following ? "コンパス：端末の向きに合わせています。赤い針が北" : "コンパス：赤い針が北")
    }
}
