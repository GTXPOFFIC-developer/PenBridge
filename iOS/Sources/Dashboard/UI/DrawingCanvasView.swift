import SwiftUI
import UIKit

public struct DrawingCanvasView: UIViewRepresentable {
    public let connection: ConnectionManager

    public init(connection: ConnectionManager) {
        self.connection = connection
    }

    public func makeUIView(context: Context) -> ApplePencilCanvasView {
        let view = ApplePencilCanvasView()
        view.connection = connection
        view.isMultipleTouchEnabled = false
        view.backgroundColor = UIColor(red: 0x0B/255.0, green: 0x0E/255.0, blue: 0x1A/255.0, alpha: 1.0)
        return view
    }

    public func updateUIView(_ uiView: ApplePencilCanvasView, context: Context) {
        uiView.connection = connection
    }
}

public class ApplePencilCanvasView: UIView {
    public var connection: ConnectionManager?

    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        handleTouches(touches, action: ProtocolConst.actionDown)
    }

    public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        handleTouches(touches, action: ProtocolConst.actionMove)
    }

    public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        handleTouches(touches, action: ProtocolConst.actionUp)
    }

    public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
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
        if touch.maximumPossibleForce > 0 {
            let normPressure = max(0, min(1.0, touch.force / touch.maximumPossibleForce))
            penEvent.pressure = UInt16(normPressure * 65535.0)
        } else {
            penEvent.pressure = penEvent.contact ? 32768 : 0
        }

        // Apple Pencil tilt: altitudeAngle (0 = parallel to surface, pi/2 = perpendicular) & azimuthAngle
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
