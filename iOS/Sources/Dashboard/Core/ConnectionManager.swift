import Foundation
import Network

public struct HostInfo: Identifiable, Hashable {
    public var id: String { "\(ip):\(port)" }
    public let ip: String
    public let name: String
    public let port: UInt16

    public init(ip: String, name: String, port: UInt16 = ProtocolConst.dataPort) {
        self.ip = ip
        self.name = name
        self.port = port
    }
}

public enum ConnState: Equatable {
    case disconnected
    case discovering
    case connecting
    case pairingRequired
    case connected(HostInfo, Int)
}

@MainActor
public class ConnectionManager: ObservableObject {
    @Published public var state: ConnState = .disconnected
    @Published public var hosts: [HostInfo] = []
    @Published public var latencyMs: Int = 0
    @Published public var lastError: String? = nil

    private let google: GoogleAccountSync
    private let deviceId: String
    private var seq: UInt16 = 1
    private var activeHost: HostInfo? = nil

    private var udpConnection: NWConnection? = nil
    private var tcpConnection: NWConnection? = nil

    public init(google: GoogleAccountSync) {
        self.google = google
        if let savedId = UserDefaults.standard.string(forKey: "dashboard_device_id") {
            self.deviceId = savedId
        } else {
            let newId = UUID().uuidString
            UserDefaults.standard.set(newId, forKey: "dashboard_device_id")
            self.deviceId = newId
        }
    }

    public func startDiscovery() {
        self.state = .discovering
        self.hosts = []
        // Start UDP listener on 41173 to receive host BEACON packets
        // And send periodic HELLO packets
    }

    public func connectTo(host: HostInfo) {
        self.activeHost = host
        self.state = .connecting

        // Send HELLO packet to host
        let helloData = Wire.hello(seq: nextSeq(), name: "iPad (Apple Pencil)", deviceId: deviceId, caps: 0x01)
        sendUdp(data: helloData, host: host.ip, port: ProtocolConst.discoveryPort)

        // If signed into Google, send Google OAuth assertion
        if google.signedIn, let email = google.email {
            let token = google.getAccessToken()
            let gAuth = Wire.googleAuth(seq: nextSeq(), email: email, accessToken: token)
            sendUdp(data: gAuth, host: host.ip, port: ProtocolConst.discoveryPort)
        }
    }

    public func submitPairCode(_ code: String) {
        guard let host = activeHost else { return }
        let pairData = Wire.pairRequest(seq: nextSeq(), code: code)
        sendUdp(data: pairData, host: host.ip, port: ProtocolConst.discoveryPort)
    }

    public func sendPen(_ event: PenEvent) {
        guard case .connected(let host, _) = state else { return }
        var ev = event
        ev.timestampMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let frameData = ev.toFrame(seq: nextSeq())
        sendUdp(data: frameData, host: host.ip, port: host.port)
    }

    private func sendUdp(data: Data, host: String, port: UInt16) {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: nwPort)
        let conn = NWConnection(to: endpoint, using: .udp)
        conn.start(queue: .global())
        conn.send(content: data, completion: .contentProcessed({ _ in
            conn.cancel()
        }))
    }

    private func nextSeq() -> UInt16 {
        seq = seq >= 65535 ? 1 : seq + 1
        return seq
    }
}
