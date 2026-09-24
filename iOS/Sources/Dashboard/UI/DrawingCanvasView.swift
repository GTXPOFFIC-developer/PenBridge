import SwiftUI
import UIKit

public struct DrawingCanvasView: UIViewRepresentable {
    public let connection: ConnectionManager
    public var stylusOnly: Bool = false
    public var penTrailEnabled: Bool = true

    public init(connection: ConnectionManager, stylusOnly: Bool = false, penTrailEnabled: Bool = true) {
        self.connection = connection
        self.stylusOnly = stylusOnly
        self.penTrailEnabled = penTrailEnabled
    }

    public func makeUIView(context: Context) -> ApplePencilCanvasView {
        let view = ApplePencilCanvasView()
        view.connection = connection
        view.stylusOnly = stylusOnly
        view.penTrailEnabled = penTrailEnabled
        view.isMultipleTouchEnabled = false
        view.backgroundColor = UIColor(red: 0x0B/255.0, green: 0x0E/255.0, blue: 0x1A/255.0, alpha: 1.0)
        return view
    }

    public func updateUIView(_ uiView: ApplePencilCanvasView, context: Context) {
        uiView.connection = connection
        uiView.stylusOnly = stylusOnly
        uiView.penTrailEnabled = penTrailEnabled
    }
}

public class ApplePencilCanvasView: UIView {
    public var connection: ConnectionManager?
    public var stylusOnly: Bool = false
    public var penTrailEnabled: Bool = true

    private struct TrailPoint {
        let point: CGPoint
        let time: TimeInterval
        let pressure: CGFloat
    }

    private var trailPoints: [TrailPoint] = []
    private var displayLink: CADisplayLink?

    public override init(frame: CGRect) {
        super.init(frame: frame)
        setupDisplayLink()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupDisplayLink()
    }

    private func setupDisplayLink() {
        displayLink = CADisplayLink(target: self, selector: #selector(onDisplayLink))
        displayLink?.add(to: .main, forMode: .common)
    }

    deinit {
        displayLink?.invalidate()
    }

    @objc private func onDisplayLink() {
        guard penTrailEnabled && !trailPoints.isEmpty else { return }
        let now = CACurrentMediaTime()
        trailPoints.removeAll { now - $0.time > 0.4 }
        setNeedsDisplay()
    }

    public override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard penTrailEnabled, trailPoints.count >= 2 else { return }

        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let now = CACurrentMediaTime()

        for i in 1..<trailPoints.count {
            let p0 = trailPoints[i - 1]
            let p1 = trailPoints[i]
            let age = now - p1.time
            if age > 0.4 { continue }
            let alpha = max(0, min(1.0, 1.0 - age / 0.4)) * 0.8
            let strokeW = 3.0 + p1.pressure * 8.0

            ctx.setStrokeColor(UIColor(red: 139/255.0, green: 92/255.0, blue: 246/255.0, alpha: alpha).cgColor)
            ctx.setLineWidth(strokeW)
            ctx.setLineCap(.round)
            ctx.setLineJoin(.round)

            ctx.beginPath()
            ctx.move(to: p0.point)
            ctx.addLine(to: p1.point)
            ctx.strokePath()
        }
    }

    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        if stylusOnly && touch.type != .pencil { return }
        handleTouches(touches, action: ProtocolConst.actionDown)
    }

    public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        if stylusOnly && touch.type != .pencil { return }
        handleTouches(touches, action: ProtocolConst.actionMove)
    }

    public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        if stylusOnly && touch.type != .pencil { return }
        handleTouches(touches, action: ProtocolConst.actionUp)
    }

    public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        if stylusOnly && touch.type != .pencil { return }
        handleTouches(touches, action: ProtocolConst.actionUp)
    }

    private func handleTouches(_ touches: Set<UITouch>, action: UInt8) {
        guard let touch = touches.first, let conn = connection else { return }
        let loc = touch.location(in: self)
        let bounds = self.bounds

        guard bounds.width > 0 && bounds.height > 0 else { return }

        var penEvent = PenEvent()
        penEvent.action = action
        penEvent.contact = (action == ProtocolConst.actionDown || action == ProtocolConst.actionMove)

        // Coordinates normalized 0..65535
        let normX = max(0, min(1.0, loc.x / bounds.width))
        let normY = max(0, min(1.0, loc.y / bounds.height))
        penEvent.xNorm = UInt16(normX * 65535.0)
        penEvent.yNorm = UInt16(normY * 65535.0)

        // Apple Pencil pressure: touch.force / touch.maximumPossibleForce
        var rawFrac: CGFloat = 0.5
        if touch.maximumPossibleForce > 0 {
            let normPressure = max(0, min(1.0, touch.force / touch.maximumPossibleForce))
            penEvent.pressure = UInt16(normPressure * 65535.0)
            rawFrac = normPressure
        } else {
            penEvent.pressure = penEvent.contact ? 32768 : 0
            rawFrac = penEvent.contact ? 0.5 : 0.0
        }

        if penTrailEnabled && penEvent.contact {
            trailPoints.append(TrailPoint(point: loc, time: CACurrentMediaTime(), pressure: rawFrac))
        }

        // Apple Pencil tilt: altitudeAngle & azimuthAngle
        if touch.type == .pencil {
            penEvent.tiltPresent = true
            let azimuth = touch.azimuthAngle(in: self)
            let altitude = touch.altitudeAngle
            // Convert to centidegrees (-900..900)
            let tiltMagnitude = (Double.pi / 2.0 - altitude) * (180.0 / Double.pi) * 10.0
            penEvent.tiltX = Int16(sin(azimuth) * tiltMagnitude)
            penEvent.tiltY = Int16(cos(azimuth) * tiltMagnitude)
        }

        conn.sendPen(penEvent)
    }
}
