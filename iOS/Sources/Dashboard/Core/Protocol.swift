import Foundation

public enum ProtocolConst {
    public static let magic = Data("DASH".utf8)
    public static let discoveryPort: UInt16 = 41173
    public static let dataPort: UInt16 = 41174
    public static let protocolVersion: UInt8 = 1

    // Packet types
    public static let typeHello: UInt8 = 0x01
    public static let typeBeacon: UInt8 = 0x02
    public static let typeHelloAck: UInt8 = 0x03
    public static let typePairRequest: UInt8 = 0x04
    public static let typePairResult: UInt8 = 0x05
    public static let typePenEvent: UInt8 = 0x06
    public static let typePing: UInt8 = 0x07
    public static let typePong: UInt8 = 0x08
    public static let typeBye: UInt8 = 0x09
    public static let typeConfig: UInt8 = 0x0A
    public static let typeGoogleAuth: UInt8 = 0x0B

    // Flags
    public static let flagAck: UInt8 = 0x01
    public static let flagFinal: UInt8 = 0x02
    public static let flagPairing: UInt8 = 0x04

    // HELLO_ACK statuses
    public static let ackAuthorized: UInt8 = 0
    public static let ackNeedsPairing: UInt8 = 1
    public static let ackMismatch: UInt8 = 2

    // Pen actions
    public static let actionUp: UInt8 = 0
    public static let actionDown: UInt8 = 1
    public static let actionMove: UInt8 = 2
    public static let actionHover: UInt8 = 3

    // Pen flags
    public static let penContact: UInt8 = 0x01
    public static let penBarrel: UInt8 = 0x02
    public static let penEraser: UInt8 = 0x04
    public static let penTilt: UInt8 = 0x08

    public static let headerSize = 10
}

public struct Frame {
    public let type: UInt8
    public let flags: UInt8
    public let seq: UInt16
    public let payload: Data

    public init(type: UInt8, flags: UInt8, seq: UInt16, payload: Data = Data()) {
        self.type = type
        self.flags = flags
        self.seq = seq
        self.payload = payload
    }
}

public enum Wire {
    public static func encode(type: UInt8, flags: UInt8, seq: UInt16, payload: Data = Data()) -> Data {
        var data = Data()
        data.append(ProtocolConst.magic)
        data.append(type)
        data.append(flags)
        var seqLE = seq.littleEndian
        var lenLE = UInt16(payload.count).littleEndian
        data.append(Data(bytes: &seqLE, count: 2))
        data.append(Data(bytes: &lenLE, count: 2))
        data.append(payload)
        return data
    }

    public static func decode(_ data: Data) -> Frame? {
        guard data.count >= ProtocolConst.headerSize else { return nil }
        guard data.prefix(4) == ProtocolConst.magic else { return nil }

        let type = data[4]
        let flags = data[5]
        let seq = data.subdata(in: 6..<8).withUnsafeBytes { $0.load(as: UInt16.self).littleEndian }
        let len = Int(data.subdata(in: 8..<10).withUnsafeBytes { $0.load(as: UInt16.self).littleEndian })

        guard data.count >= ProtocolConst.headerSize + len else { return nil }
        let payload = data.subdata(in: ProtocolConst.headerSize..<(ProtocolConst.headerSize + len))

        return Frame(type: type, flags: flags, seq: seq, payload: payload)
    }

    public static func hello(seq: UInt16, name: String, deviceId: String, caps: UInt8 = 0x01) -> Data {
        let nameData = Data(name.utf8)
        let idData = Data(deviceId.utf8)

        var payload = Data()
        payload.append(ProtocolConst.protocolVersion)
        payload.append(caps)
        payload.append(UInt8(min(nameData.count, 255)))
        payload.append(nameData)
        payload.append(UInt8(min(idData.count, 255)))
        payload.append(idData)
        var port: UInt16 = 0
        payload.append(Data(bytes: &port, count: 2))

        return encode(type: ProtocolConst.typeHello, flags: 0, seq: seq, payload: payload)
    }

    public static func googleAuth(seq: UInt16, email: String, accessToken: String) -> Data {
        let em = Data(email.utf8)
        let tk = Data(accessToken.utf8)

        var payload = Data()
        var emLen = UInt16(em.count).littleEndian
        var tkLen = UInt16(tk.count).littleEndian
        payload.append(Data(bytes: &emLen, count: 2))
        payload.append(em)
        payload.append(Data(bytes: &tkLen, count: 2))
        payload.append(tk)

        return encode(type: ProtocolConst.typeGoogleAuth, flags: 0, seq: seq, payload: payload)
    }

    public static func ping(seq: UInt16) -> Data {
        encode(type: ProtocolConst.typePing, flags: 0, seq: seq)
    }

    public static func pong(seq: UInt16) -> Data {
        encode(type: ProtocolConst.typePong, flags: ProtocolConst.flagAck, seq: seq)
    }

    public static func bye(seq: UInt16) -> Data {
        encode(type: ProtocolConst.typeBye, flags: 0, seq: seq)
    }

    public static func pairRequest(seq: UInt16, code: String) -> Data {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        return encode(type: ProtocolConst.typePairRequest, flags: ProtocolConst.flagPairing, seq: seq, payload: Data(trimmed.utf8))
    }
}

public struct PenEvent {
    public var action: UInt8 = ProtocolConst.actionHover
    public var contact: Bool = false
    public var barrel: Bool = false
    public var eraser: Bool = false
    public var tiltPresent: Bool = false
    public var tiltX: Int16 = 0       // centidegrees -900..900
    public var tiltY: Int16 = 0
    public var pressure: UInt16 = 0    // 0..65535
    public var xNorm: UInt16 = 0       // 0..65535
    public var yNorm: UInt16 = 0
    public var timestampMs: UInt64 = 0

    public init() {}

    public func toFrame(seq: UInt16) -> Data {
        var payload = Data()
        payload.append(action)

        var flags: UInt8 = 0
        if contact { flags |= ProtocolConst.penContact }
        if barrel { flags |= ProtocolConst.penBarrel }
        if eraser { flags |= ProtocolConst.penEraser }
        if tiltPresent { flags |= ProtocolConst.penTilt }
        payload.append(flags)

        var tx = tiltX.littleEndian
        var ty = tiltY.littleEndian
        var pr = pressure.littleEndian
        var xn = xNorm.littleEndian
        var yn = yNorm.littleEndian
        var ts = timestampMs.littleEndian

        payload.append(Data(bytes: &tx, count: 2))
        payload.append(Data(bytes: &ty, count: 2))
        payload.append(Data(bytes: &pr, count: 2))
        payload.append(Data(bytes: &xn, count: 2))
        payload.append(Data(bytes: &yn, count: 2))
        payload.append(Data(bytes: &ts, count: 8))

        return Wire.encode(type: ProtocolConst.typePenEvent, flags: 0, seq: seq, payload: payload)
    }
}
