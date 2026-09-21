# Dashboard Wire Protocol

Specification shared by the Android tablet app (client/device) and the
Windows host app (server). Both apps implement this exact binary format.

All multi-byte integers are **little-endian**.

## Ports

| Port  | Transport | Purpose                              |
|-------|-----------|--------------------------------------|
| 41173 | UDP       | Discovery / beacons / hello handshake |
| 41174 | UDP + TCP | Data channel (pen events)            |

Wi-Fi devices reach the host over UDP 41174. USB devices reach the host
over **TCP** via `adb reverse tcp:41174 tcp:41174` (localhost only).

## Frame envelope

Every packet (discovery and data alike) is wrapped in the frame below.

```
Offset  Size  Field
0       4     magic          ASCII "DASH"
4       1     type           see packet types
5       1     flags          bit flags
6       2     seq            sequence number (increment per sender)
8       2     payloadLen     length of payload in bytes
10      n     payload        packet-specific
```

`flags` bits:

| Bit | Name            | Meaning                                      |
|-----|-----------------|----------------------------------------------|
| 0   | FLAG_ACK        | packet acknowledges a previous `seq`         |
| 1   | FLAG_FINAL      | terminal state (see HELLO_ACK)               |
| 2   | FLAG_PAIRING    | device is requesting pairing                 |

## Packet types

| 0x01 | HELLO       | device announces itself to the host |
| 0x02 | BEACON      | host announces itself to devices    |
| 0x03 | HELLO_ACK   | host replies to a HELLO             |
| 0x04 | PAIR_REQUEST| device submits a 6-digit code       |
| 0x05 | PAIR_RESULT | host accepts / denies a pairing     |
| 0x06 | PEN_EVENT   | pen/touch sample                    |
| 0x07 | PING        | keepalive / latency probe           |
| 0x08 | PONG        | reply to PING                       |
| 0x09 | BYE         | graceful disconnect                 |
| 0x0A | CONFIG      | mapping + pressure curve (either direction)|
| 0x0B | GOOGLE_AUTH | Google account identity assertion (device -> host) |

## HELLO (device -> broadcast / host)

```
u8   protocolVersion   = 1
u8   capabilities      bit0 pen, bit1 touch
u8   nameLen
str  name              device friendly name (ASCII, nameLen bytes)
u8   idLen
str  deviceId          stable UUID string (idLen bytes)
u16  deviceUdpPort     optional inbound port for host-initiated connects (0 = none)
```

The device broadcasts HELLO periodically to UDP 41173 (255.255.255.255)
and unicasts it to any host it remembers. The host may answer with a
unicast HELLO_ACK to the packet's source address.

## BEACON (host -> broadcast)

```
u8   nameLen
str  name               host friendly name (ASCII)
u16  hostDataPort       UDP port the host listens on (41174)
```

The Windows host broadcasts BEACON every 2 seconds on UDP 41173 so
tablets can discover it without sending a HELLO first.

## HELLO_ACK (host -> device, unicast)

```
u8   status            0 = authorized  1 = needs pairing  2 = protocol mismatch
```

`FLAG_FINAL` is set on status 0/2 (terminal). Status 1 means the device
must show a pairing dialog and send PAIR_REQUEST.

## PAIR_REQUEST (device -> host)

```
str  code              exactly 6 ASCII digits, no terminator
```

The host validates the code entered on its **Devices** screen, then
answers with PAIR_RESULT. On success the host records the deviceId from
HELLO in its allow-list so future HELLOs get status 0.

## PAIR_RESULT (host -> device)

```
u8   accepted          1 = accepted, 0 = denied
```

## PEN_EVENT (device -> host)

```
u8   action            0 = UP  1 = DOWN  2 = MOVE  3 = HOVER
u8   flags             bit0 contact, bit1 barrel, bit2 eraser, bit3 tilt present
i16  tiltX             tilt along X in centidegrees (-900..900)
i16  tiltY             tilt along Y in centidegrees (-900..900)
u16  pressure          0..65535 (scaled from stylus normalized pressure)
u16  xNormalized       0..65535 fraction of the tablet surface
u16  yNormalized       0..65535 fraction of the tablet surface
u64  timestampMs       epoch milliseconds (device clock)
```

Normalized coords are the raw full-surface coords of the **tablet**; the
host applies the display-mapping region. Total payload = 20 bytes.

Action semantics (moved, down/up/hover flagged explicitly so stroke
engines in Krita / Photoshop / Clip Studio register):

| action | contact flag | generated pointer |
|--------|--------------|-------------------|
| HOVER  | 0            | in-range hover    |
| DOWN   | 1            | pen down          |
| MOVE   | 1            | in-contact move   |
| MOVE   | 0            | hover move        |
| UP     | 0            | pen up            |

## CONFIG (either direction)

Synchronizes display mapping, rotation, tilt toggle and the pressure
response curve. The host pushes its authoritative mapping; the device
shares its curve / tilt preference up so both stay in sync.

```
u16  regionX0, regionY0, regionX1, regionY1   normalized 0..65535
u16  rotationDeg                              multiples of 90
u8   flags          bit0 tilt enabled, bit1 invert X, bit2 invert Y
u8   curveCount     number of curve points (1..16)
u16  curve[]        pairs: in[i], out[i]      normalized 0..65535
```

## GOOGLE_AUTH (device -> host)

Chrome-Remote-Desktop-style auto-pairing with **no backend**: when the
tablet is signed into Google it sends its OAuth token + email straight to
the host over the live link.

```
u16  emailLen
str  email            account email (ASCII)
u16  tokenLen
str  accessToken      Google OAuth access token (ASCII)
```

The host verifies `accessToken` with
`https://oauth2.googleapis.com/tokeninfo` (free endpoint). If the returned
`email` matches the account the host itself is signed into, the device is
added to the allow-list and receives HELLO_ACK status 0 immediately;
otherwise normal 6-digit pairing continues.

## PING / PONG

No payload. PONG copies the `seq` of the PING into its own `seq` and sets
`FLAG_ACK`. Round-trip latency = `now - t(ping seq)`, reported by both apps.

## BYE

No payload. Sent before graceful disconnect, receiver forgets the peer.

## Heartbeat & reconnect

The device PINGs its host every 500 ms. Missing 3 consecutive PONGs marks
the link stale: Wi-Fi devices re-broadcast HELLO; USB devices retry the
`adb reverse` mapping and TCP connect. Hosts never initiate pen traffic,
they only reply to PINGs.

## Security

- Devices are anonymous until the host allow-list contains their `deviceId`
  (HELLO_ACK status 0).
- Pairing is a 6-digit one-time code entered in the host's Devices screen.
- Optional Google account link: a tablet signed into the **same** account as
  the host is auto-authorized (GOOGLE_AUTH -> tokeninfo verify). No Firestore,
  no relay, nothing stored in the cloud.
- Hosts silently drop PEN_EVENT / PING traffic from un-authorized senders.
- USB (`adb reverse`) traffic is confined to localhost.