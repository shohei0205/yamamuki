import SwiftUI
import UIKit
import YamamukiCore

/// Canvasと同じ座標系でタップと複数指を扱う。上に重なるボタンは通常のSwiftUI操作を保つ。
struct DialTouchSurface: UIViewRepresentable {
    let onPan: (Double, Double, Double) -> Void
    let onTransform: (Double, Double, PlanOffset, PlanOffset, Double) -> Void
    let onTap: (CGPoint) -> Void

    func makeUIView(context: Context) -> TouchView {
        let view = TouchView()
        view.isMultipleTouchEnabled = true
        view.backgroundColor = .clear
        view.callbacks = self
        return view
    }

    func updateUIView(_ view: TouchView, context: Context) { view.callbacks = self }

    final class TouchView: UIView {
        var callbacks: DialTouchSurface?
        private var active: [UITouch] = []
        private var previous: [CGPoint] = []
        private var start = CGPoint.zero
        private var dragging = false
        private var multiTouch = false

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            if active.isEmpty, let first = touches.first {
                start = first.location(in: self)
                dragging = false
                multiTouch = false
            }
            active.append(contentsOf: touches)
            if active.count > 1 { multiTouch = true }
            previous = active.map { $0.location(in: self) }
        }

        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            let points = active.map { $0.location(in: self) }
            defer { previous = points }
            guard points.count == previous.count else { return }
            if points.count == 1 && !multiTouch {
                let p = points[0]
                let distance = hypot(p.x - start.x, p.y - start.y)
                guard dragging || distance > 8 else { return }
                let old = dragging ? previous[0] : start
                dragging = true
                callbacks?.onPan(Double(p.x - old.x), Double(p.y - old.y), Double(bounds.height) - DialGeometry.chartInset)
            } else if points.count == 2 {
                let a = previous[0], b = previous[1], c = points[0], d = points[1]
                let oldDistance = hypot(b.x - a.x, b.y - a.y)
                guard oldDistance > 0 else { return }
                let zoom = Double(hypot(d.x - c.x, d.y - c.y) / oldDistance)
                let angle = Double(atan2(d.y - c.y, d.x - c.x) - atan2(b.y - a.y, b.x - a.x)) * 180 / .pi
                func midpoint(_ p: CGPoint, _ q: CGPoint) -> PlanOffset {
                    PlanOffset(x: Double((p.x + q.x - bounds.width) / 2),
                        y: Double((p.y + q.y) / 2 - bounds.height) + DialGeometry.originBottom)
                }
                callbacks?.onTransform(zoom, Heading.delta(0, angle), midpoint(a, b), midpoint(c, d), Double(bounds.height) - DialGeometry.chartInset)
            }
        }

        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
            if !dragging && !multiTouch, let touch = touches.first {
                let p = touch.location(in: self)
                if hypot(p.x - start.x, p.y - start.y) <= 8 { callbacks?.onTap(p) }
            }
            active.removeAll { touches.contains($0) }
            previous = active.map { $0.location(in: self) }
        }

        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
            // 残った指を新しいドラッグやタップとして扱わない。
            multiTouch = true
            active.removeAll { touches.contains($0) }
            previous = active.map { $0.location(in: self) }
        }
    }
}
