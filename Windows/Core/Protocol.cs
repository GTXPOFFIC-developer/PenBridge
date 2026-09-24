using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace DashboardHost.Core
{
    public static class ProtocolConst
    {
        public static readonly byte[] Magic = Encoding.ASCII.GetBytes("DASH");
        public const int DiscoveryPort = 41173;
        public const int DataPort = 41174;
        public const byte ProtocolVersion = 1;

        // Packet types
        public const byte TypeHello = 0x01;
        public const byte TypeBeacon = 0x02;
        public const byte TypeHelloAck = 0x03;
        public const byte TypePairRequest = 0x04;
        public const byte TypePairResult = 0x05;
        public const byte TypePenEvent = 0x06;
        public const byte TypePing = 0x07;
        public const byte TypePong = 0x08;
        public const byte TypeBye = 0x09;
        public const byte TypeConfig = 0x0A;
        public const byte TypeGoogleAuth = 0x0B;

        // Flags
        public const byte FlagAck = 0x01;
        public const byte FlagFinal = 0x02;
        public const byte FlagPairing = 0x04;

        // HELLO_ACK statuses
        public const byte AckAuthorized = 0;
        public const byte AckNeedsPairing = 1;
        public const byte AckMismatch = 2;

        // Pen actions
        public const byte ActionUp = 0;
        public const byte ActionDown = 1;
        public const byte ActionMove = 2;
        public const byte ActionHover = 3;
        public const byte ActionScroll = 4;

        // Pen flags
        public const byte PenContact = 0x01;
        public const byte PenBarrel = 0x02;
        public const byte PenEraser = 0x04;
        public const byte PenTilt = 0x08;
        public const byte PenRelative = 0x10;
        public const byte PenMiddle = 0x20;
        public const byte PenDoubleClick = 0x40;
        public const byte PenUndo = 0x80;

        public const int HeaderSize = 10;
    }

    public sealed class Frame
    {
        public byte Type { get; }
        public byte Flags { get; }
        public ushort Seq { get; }
        public byte[] Payload { get; }

        public Frame(byte type, byte flags, ushort seq, byte[] payload)
        {
            Type = type;
            Flags = flags;
            Seq = seq;
            Payload = payload;
        }

        public override string ToString() =>
            $"Frame(Type=0x{Type:X2}, Flags=0x{Flags:X2}, Seq={Seq}, Len={Payload.Length})";
    }

    public static class Wire
    {
        public static byte[] Encode(byte type, byte flags, ushort seq, byte[]? payload = null)
        {
            payload ??= Array.Empty<byte>();
            byte[] buffer = new byte[ProtocolConst.HeaderSize + payload.Length];

            // Magic "DASH"
            buffer[0] = (byte)'D';
            buffer[1] = (byte)'A';
            buffer[2] = (byte)'S';
            buffer[3] = (byte)'H';

            buffer[4] = type;
            buffer[5] = flags;
            BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(6, 2), seq);
            BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(8, 2), (ushort)payload.Length);

            if (payload.Length > 0)
            {
                Buffer.BlockCopy(payload, 0, buffer, ProtocolConst.HeaderSize, payload.Length);
            }

            return buffer;
        }

        public static Frame? Decode(ReadOnlySpan<byte> bytes)
        {
            if (bytes.Length < ProtocolConst.HeaderSize)
                return null;

            if (bytes[0] != (byte)'D' || bytes[1] != (byte)'A' ||
                bytes[2] != (byte)'S' || bytes[3] != (byte)'H')
            {
                return null;
            }

            byte type = bytes[4];
            byte flags = bytes[5];
            ushort seq = BinaryPrimitives.ReadUInt16LittleEndian(bytes.Slice(6, 2));
            ushort len = BinaryPrimitives.ReadUInt16LittleEndian(bytes.Slice(8, 2));

            if (bytes.Length < ProtocolConst.HeaderSize + len)
                return null;

            byte[] payload = bytes.Slice(ProtocolConst.HeaderSize, len).ToArray();
            return new Frame(type, flags, seq, payload);
        }

        public static byte[] Beacon(ushort seq, string name, ushort hostDataPort)
        {
            byte[] nameBytes = Encoding.ASCII.GetBytes(name);
            byte nameLen = (byte)Math.Min(nameBytes.Length, 64);

            byte[] payload = new byte[1 + nameLen + 2];
            payload[0] = nameLen;
            Buffer.BlockCopy(nameBytes, 0, payload, 1, nameLen);
            BinaryPrimitives.WriteUInt16LittleEndian(payload.AsSpan(1 + nameLen, 2), hostDataPort);

            return Encode(ProtocolConst.TypeBeacon, 0, seq, payload);
        }

        public static byte[] HelloAck(ushort seq, byte status, bool isFinal)
        {
            byte flags = isFinal ? ProtocolConst.FlagFinal : (byte)0;
            return Encode(ProtocolConst.TypeHelloAck, flags, seq, new[] { status });
        }

        public static byte[] PairResult(ushort seq, bool accepted)
        {
            return Encode(ProtocolConst.TypePairResult, 0, seq, new[] { (byte)(accepted ? 1 : 0) });
        }

        public static byte[] Pong(ushort seq)
        {
            return Encode(ProtocolConst.TypePong, ProtocolConst.FlagAck, seq, Array.Empty<byte>());
        }

        public static byte[] Ping(ushort seq)
        {
            return Encode(ProtocolConst.TypePing, 0, seq, Array.Empty<byte>());
        }

        public static byte[] Bye(ushort seq)
        {
            return Encode(ProtocolConst.TypeBye, 0, seq, Array.Empty<byte>());
        }
    }

    public sealed class HelloPayload
    {
        public byte Version { get; set; }
        public byte Capabilities { get; set; }
        public string Name { get; set; } = string.Empty;
        public string DeviceId { get; set; } = string.Empty;
        public ushort DeviceUdpPort { get; set; }

        public static HelloPayload? Parse(byte[] payload)
        {
            if (payload.Length < 6) return null;
            try
            {
                var span = payload.AsSpan();
                byte version = span[0];
                byte caps = span[1];
                byte nameLen = span[2];
                if (span.Length < 3 + nameLen + 1) return null;
                string name = Encoding.ASCII.GetString(span.Slice(3, nameLen));

                int offset = 3 + nameLen;
                byte idLen = span[offset++];
                if (span.Length < offset + idLen + 2) return null;
                string id = Encoding.ASCII.GetString(span.Slice(offset, idLen));
                offset += idLen;

                ushort port = BinaryPrimitives.ReadUInt16LittleEndian(span.Slice(offset, 2));

                return new HelloPayload
                {
                    Version = version,
                    Capabilities = caps,
                    Name = name,
                    DeviceId = id,
                    DeviceUdpPort = port
                };
            }
            catch
            {
                return null;
            }
        }
    }

    public sealed class GoogleAuthPayload
    {
        public string Email { get; set; } = string.Empty;
        public string AccessToken { get; set; } = string.Empty;

        public static GoogleAuthPayload? Parse(byte[] payload)
        {
            if (payload.Length < 4) return null;
            try
            {
                var span = payload.AsSpan();
                ushort emailLen = BinaryPrimitives.ReadUInt16LittleEndian(span.Slice(0, 2));
                if (span.Length < 2 + emailLen + 2) return null;
                string email = Encoding.ASCII.GetString(span.Slice(2, emailLen));

                int offset = 2 + emailLen;
                ushort tokenLen = BinaryPrimitives.ReadUInt16LittleEndian(span.Slice(offset, 2));
                offset += 2;
                if (span.Length < offset + tokenLen) return null;
                string token = Encoding.ASCII.GetString(span.Slice(offset, tokenLen));

                return new GoogleAuthPayload
                {
                    Email = email,
                    AccessToken = token
                };
            }
            catch
            {
                return null;
            }
        }
    }

    public sealed class PenEventPayload
    {
        public byte Action { get; set; }
        public byte Flags { get; set; }
        public short TiltX { get; set; }
        public short TiltY { get; set; }
        public ushort Pressure { get; set; }
        public ushort XNorm { get; set; }
        public ushort YNorm { get; set; }
        public ulong TimestampMs { get; set; }

        public bool Contact => (Flags & ProtocolConst.PenContact) != 0;
        public bool Barrel => (Flags & ProtocolConst.PenBarrel) != 0;
        public bool Eraser => (Flags & ProtocolConst.PenEraser) != 0;
        public bool Middle => (Flags & ProtocolConst.PenMiddle) != 0;
        public bool DoubleClick => (Flags & ProtocolConst.PenDoubleClick) != 0;
        public bool Undo => (Flags & ProtocolConst.PenUndo) != 0;
        public bool TiltPresent => (Flags & ProtocolConst.PenTilt) != 0;
        public bool Relative => (Flags & ProtocolConst.PenRelative) != 0;

        public static PenEventPayload? Parse(byte[] payload)
        {
            if (payload.Length < 20) return null;
            try
            {
                var span = payload.AsSpan();
                return new PenEventPayload
                {
                    Action = span[0],
                    Flags = span[1],
                    TiltX = BinaryPrimitives.ReadInt16LittleEndian(span.Slice(2, 2)),
                    TiltY = BinaryPrimitives.ReadInt16LittleEndian(span.Slice(4, 2)),
                    Pressure = BinaryPrimitives.ReadUInt16LittleEndian(span.Slice(6, 2)),
                    XNorm = BinaryPrimitives.ReadUInt16LittleEndian(span.Slice(8, 2)),
                    YNorm = BinaryPrimitives.ReadUInt16LittleEndian(span.Slice(10, 2)),
                    TimestampMs = BinaryPrimitives.ReadUInt64LittleEndian(span.Slice(12, 8))
                };
            }
            catch
            {
                return null;
            }
        }
    }
}
