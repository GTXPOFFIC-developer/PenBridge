# host-probe.ps1 — tiny Dashboard test host (no Python, no build).
# Implements the PROTOCOL.md host side just enough to verify the Android app:
#   - UDP 41173 : listens for HELLO, replies HELLO_ACK(0) = authorized; ignores GOOGLE_AUTH
#   - UDP 41174 : receives PEN_EVENTs, replies PONG to PINGs
#   - TCP 41174 : same over USB (`adb reverse tcp:41174 tcp:41174`)
# It prints live sample stats. Ctrl+C to stop.

$ErrorActionPreference = 'Stop'
$DATA_PORT = 41174
$DISCOVERY_PORT = 41173
$MAGIC = [byte[]]([char]'D','A','S','H' | ForEach-Object { [byte]$_ })

function New-Frame($type, $flags, $seq, $payload) {
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([byte[]]$MAGIC)
    $bw.Write([byte]$type); $bw.Write([byte]$flags)
    $bw.Write([uint16]$seq); $bw.Write([uint16]$payload.Length)
    $bw.Write($payload)
    $bw.Flush(); return $ms.ToArray()
}

function Read-Frame($bytes) {
    if ($bytes.Length -lt 10) { return $null }
    if ($bytes[0] -ne $MAGIC[0] -or $bytes[1] -ne $MAGIC[1] -or $bytes[2] -ne $MAGIC[2] -or $bytes[3] -ne $MAGIC[3]) { return $null }
    $type = $bytes[4]; $flags = $bytes[5]
    $bl = New-Object System.IO.BinaryReader (New-Object System.IO.MemoryStream($bytes, 6, 4))
    $seq = $bl.ReadUInt16(); $len = $bl.ReadUInt16()
    if ($bytes.Length -lt (10 + $len)) { return $null }
    return @{ type=$type; flags=$flags; seq=$seq; payload=$bytes[10..(9+$len)] }
}

$stats = [ordered]@{
    Started = (Get-Date)
    Hello = 0; Pings = 0; Pongs = 0; PenEvents = 0; Down = 0; Up = 0; Hover = 0
    LastPressure = 0.0; LastX = -1; LastY = -1
}
$sw = [System.Diagnostics.Stopwatch]::StartNew()

$udpDiscover = New-Object System.Net.Sockets.UdpClient($DISCOVERY_PORT)
$udpData = New-Object System.Net.Sockets.UdpClient($DATA_PORT)
$tcp = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $DATA_PORT)
$tcp.Start()

function Handle-Frame($f, $client, $send) {
    if (-not $f) { return }
    switch ($f.type) {
        0x01 { # HELLO -> authorized
            $stats.Hello++
            $ack = New-Frame 0x03 0x02 1 ([byte[]](0))   # HELLO_ACK, FLAG_FINAL, status authorized
            & $send $ack
        }
        0x06 { # PEN_EVENT
            $stats.PenEvents++
            $p = $f.payload
            if ($p.Length -ge 20) {
                $action = $p[0]; $flags = $p[1]
                $tiltX = [int16]([int]$p[2] + ([int]$p[3] -shl 8))
                $tiltY = [int16]([int]$p[4] + ([int]$p[5] -shl 8))
                $press = [int]$p[6] + ([int]$p[7] -shl 8)
                $x = [int]$p[8] + ([int]$p[9] -shl 8)
                $y = [int]$p[10] + ([int]$p[11] -shl 8)
                $stats.LastPressure = [Math]::Round($press / 65535.0, 3)
                $stats.LastX = $x; $stats.LastY = $y
                switch ($action) { 0 { $stats.Up++ } 1 { $stats.Down++ } 3 { $stats.Hover++ } }
            }
        }
        0x07 { # PING -> PONG
            $stats.Pings++
            $pong = New-Frame 0x08 0x01 ($f.seq) ([byte[]]@())
            & $send $pong
        }
    }
}

# UDP discovery + data sockets (single-thread poll loop)
$udpEnd  = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
$udpEnd2 = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
$tcpClients = @{}
$buf = New-Object byte[] 512

while ($true) {
    # UDP 41173 (hello / replies)
    try {
        if ($udpDiscover.Client.Poll(10, [System.Net.Sockets.SelectMode]::SelectRead)) {
            $data = $udpDiscover.Receive([ref]$udpEnd)
            $f = Read-Frame $data
            $sender = [System.Net.Sockets.UdpClient]::new()
            Handle-Frame $f $null { $s = $udpDiscover; $s.Send($args[0], $args[0].Length, $udpEnd) | Out-Null }
        }
    } catch {}
    # UDP 41174 (pen / ping)
    try {
        if ($udpData.Client.Poll(10, [System.Net.Sockets.SelectMode]::SelectRead)) {
            $data = $udpData.Receive([ref]$udpEnd2)
            $f = Read-Frame $data
            Handle-Frame $f $null { $s = $udpData; $s.Send($args[0], $args[0].Length, $udpEnd2) | Out-Null }
        }
    } catch {}
    # TCP 41174 (USB)
    if ($tcp.Pending()) {
        $c = $tcp.AcceptTcpClient()
        $c.NoDelay = $true
        $tcpClients[$c] = New-Object byte[] 512
    }
    foreach ($c in @($tcpClients.Keys)) {
        try {
            $bs = $c.GetStream()
            $n = $bs.Read($tcpClients[$c], 0, 512)
            if ($n -gt 0) {
                $f = Read-Frame $tcpClients[$c]
                Handle-Frame $f $c { $bs.Write($args[0], 0, $args[0].Length) }
            } elseif ($n -eq 0) {
                $c.Close(); $tcpClients.Remove($c)
            }
        } catch {
            $c.Close(); $tcpClients.Remove($c)
        }
    }
    # status line every second
    if ($sw.ElapsedMilliseconds -gt 1000) {
        $hz = [Math]::Round($stats.PenEvents / $sw.Elapsed.TotalSeconds, 1)
        $line = "samples={0} ({1}Hz hist/down={2}) press={3} x={4} y={5} hello={6} ping={7}" -f `
            $stats.PenEvents, $hz, ($stats.Down + $stats.Up + $stats.Hover), $stats.LastPressure, $stats.LastX, $stats.LastY, $stats.Hello, $stats.Pings
        Write-Host $line
        $sw.Restart(); $stats.PenEvents = 0; $stats.Down = 0; $stats.Up = 0; $stats.Hover = 0
    }
    Start-Sleep -Milliseconds 20
}