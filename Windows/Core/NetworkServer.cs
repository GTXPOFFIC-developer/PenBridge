using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace DashboardHost.Core
{
    public sealed class NetworkServer : IDisposable
    {
        private readonly DeviceManager _deviceManager;
        private readonly GoogleAuthService _googleAuth;
        private readonly InputInjector _inputInjector;

        private UdpClient? _discoveryClient;
        private UdpClient? _dataClient;
        private TcpListener? _tcpListener;
        private MdnsAdvertiser? _mdns;

        private CancellationTokenSource? _cts;
        private bool _isRunning = false;
        private ushort _seq = 1;

        // Peer endpoint -> (DeviceId, DeviceName)
        private readonly ConcurrentDictionary<string, (string DeviceId, string Name)> _peerInfo = new();

        public bool IsRunning => _isRunning;
        public string HostName { get; set; } = Environment.MachineName;

        public event Action<string>? LogMessage;
        public event Action<bool>? ServerStateChanged;

        /// <summary>Allows external callers to emit a log entry into the server's log stream.</summary>
        public void Log(string message) => LogMessage?.Invoke(message);

        public NetworkServer(DeviceManager deviceManager, GoogleAuthService googleAuth, InputInjector inputInjector)
        {
            _deviceManager = deviceManager;
            _googleAuth = googleAuth;
            _inputInjector = inputInjector;
            _deviceManager.DeviceRevoked += OnDeviceRevoked;
        }

        private void OnDeviceRevoked(string deviceId)
        {
            try
            {
                foreach (var kvp in _peerInfo)
                {
                    if (string.Equals(kvp.Value.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase))
                    {
                        string ipStr = kvp.Key;
                        _peerInfo.TryRemove(ipStr, out _);

                        if (IPAddress.TryParse(ipStr, out var ip))
                        {
                            byte[] bye = Wire.Bye(NextSeq());
                            _dataClient?.Send(bye, bye.Length, new IPEndPoint(ip, ProtocolConst.DataPort));
                            _discoveryClient?.Send(bye, bye.Length, new IPEndPoint(ip, ProtocolConst.DiscoveryPort));
                        }
                    }
                }
                LogMessage?.Invoke($"Device '{deviceId}' revoked. Sent BYE disconnect frame.");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"OnDeviceRevoked error: {ex.Message}");
            }
        }

        public void Start()
        {
            if (_isRunning) return;
            _isRunning = true;
            _cts = new CancellationTokenSource();

            try
            {
                // 1. Discovery UDP on 41173
                _discoveryClient = new UdpClient();
                _discoveryClient.Client.ExclusiveAddressUse = false;
                _discoveryClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _discoveryClient.Client.Bind(new IPEndPoint(IPAddress.Any, ProtocolConst.DiscoveryPort));
                _discoveryClient.EnableBroadcast = true;

                // 2. Data UDP on 41174
                _dataClient = new UdpClient();
                _dataClient.Client.ExclusiveAddressUse = false;
                _dataClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _dataClient.Client.Bind(new IPEndPoint(IPAddress.Any, ProtocolConst.DataPort));

                // 3. TCP on 127.0.0.1:41174 for USB ADB reverse
                _tcpListener = new TcpListener(IPAddress.Loopback, ProtocolConst.DataPort);
                _tcpListener.Server.ExclusiveAddressUse = false;
                _tcpListener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _tcpListener.Start();

                var token = _cts.Token;
                Task.Run(() => DiscoveryReceiveLoopAsync(_discoveryClient, token), token);
                Task.Run(() => BeaconBroadcastLoopAsync(_discoveryClient, token), token);
                Task.Run(() => DataReceiveLoopAsync(_dataClient, token), token);
                Task.Run(() => TcpAcceptLoopAsync(_tcpListener, token), token);

                // 4. mDNS advertisement (_dashboard._tcp) so Android/iOS find us automatically
                _mdns = new MdnsAdvertiser(HostName, ProtocolConst.DataPort);
                _mdns.Start();

                LogMessage?.Invoke($"Server started. Listening on UDP {ProtocolConst.DiscoveryPort}/{ProtocolConst.DataPort} and TCP {ProtocolConst.DataPort}. mDNS active.");
                ServerStateChanged?.Invoke(true);
            }
            catch (Exception ex)
            {
                LogMessage?.Invoke($"Failed to start server: {ex.Message}");
                Stop();
            }
        }

        public void Stop()
        {
            if (!_isRunning) return;
            _isRunning = false;

            try { _cts?.Cancel(); } catch { }
            try { _mdns?.Stop(); } catch { }
            try { _discoveryClient?.Close(); } catch { }
            try { _dataClient?.Close(); } catch { }
            try { _tcpListener?.Stop(); } catch { }

            _mdns = null;
            _discoveryClient = null;
            _dataClient = null;
            _tcpListener = null;
            _cts = null;

            LogMessage?.Invoke("Server stopped.");
            ServerStateChanged?.Invoke(false);
        }

        private ushort NextSeq()
        {
            unchecked
            {
                _seq = _seq >= 65535 ? (ushort)1 : (ushort)(_seq + 1);
                return _seq;
            }
        }

        #region Discovery & Handshake (UDP 41173)

        private async Task BeaconBroadcastLoopAsync(UdpClient client, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    byte[] beacon = Wire.Beacon(NextSeq(), HostName, ProtocolConst.DataPort);
                    // 1. General broadcast (255.255.255.255)
                    await client.SendAsync(beacon, beacon.Length, new IPEndPoint(IPAddress.Broadcast, ProtocolConst.DiscoveryPort));

                    // 2. Specific interface directed broadcasts (e.g. 192.168.1.255)
                    foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                    {
                        if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up ||
                            nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
                            continue;

                        foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
                        {
                            if (unicast.Address.AddressFamily == AddressFamily.InterNetwork && unicast.IPv4Mask != null)
                            {
                                byte[] ipBytes = unicast.Address.GetAddressBytes();
                                byte[] maskBytes = unicast.IPv4Mask.GetAddressBytes();
                                byte[] broadcastBytes = new byte[4];
                                for (int i = 0; i < 4; i++)
                                    broadcastBytes[i] = (byte)(ipBytes[i] | ~maskBytes[i]);
                                var directedBroadcast = new IPAddress(broadcastBytes);
                                await client.SendAsync(beacon, beacon.Length, new IPEndPoint(directedBroadcast, ProtocolConst.DiscoveryPort));
                            }
                        }
                    }

                    // 3. Known peer unicast (to reach tablet directly even if router blocks broadcasts)
                    foreach (var peer in _peerInfo.Keys)
                    {
                        if (IPAddress.TryParse(peer, out var peerIp))
                        {
                            await client.SendAsync(beacon, beacon.Length, new IPEndPoint(peerIp, ProtocolConst.DiscoveryPort));
                        }
                    }
                }
                catch (Exception ex) when (!token.IsCancellationRequested)
                {
                    Debug.WriteLine($"Beacon error: {ex.Message}");
                }

                await Task.Delay(1500, token);
            }
        }

        private async Task DiscoveryReceiveLoopAsync(UdpClient client, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var result = await client.ReceiveAsync(token);
                    var frame = Wire.Decode(result.Buffer);
                    if (frame == null) continue;

                    var remoteEndpoint = result.RemoteEndPoint;
                    string remoteIp = remoteEndpoint.Address.ToString();

                    switch (frame.Type)
                    {
                        case ProtocolConst.TypeHello:
                            HandleHello(frame, remoteEndpoint);
                            break;

                        case ProtocolConst.TypePairRequest:
                            HandlePairRequest(frame, remoteEndpoint);
                            break;

                        case ProtocolConst.TypeGoogleAuth:
                            _ = HandleGoogleAuthAsync(frame, remoteEndpoint);
                            break;
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    Debug.WriteLine($"Discovery recv error: {ex.Message}");
                }
            }
        }

        private void HandleHello(Frame frame, IPEndPoint remote)
        {
            var hello = HelloPayload.Parse(frame.Payload);
            if (hello == null) return;

            string remoteIp = remote.Address.ToString();
            _peerInfo[remoteIp] = (hello.DeviceId, hello.Name);

            LogMessage?.Invoke($"Received HELLO from '{hello.Name}' ({remoteIp}, DeviceId: {hello.DeviceId})");

            bool authorized = _deviceManager.IsAuthorized(hello.DeviceId);
            if (authorized)
            {
                _deviceManager.UpdateDeviceActivity(hello.DeviceId, remoteIp);
                byte[] ack = Wire.HelloAck(NextSeq(), ProtocolConst.AckAuthorized, isFinal: true);
                _discoveryClient?.Send(ack, ack.Length, remote);
                LogMessage?.Invoke($"Device '{hello.Name}' is authorized. Sent HELLO_ACK (Authorized).");
            }
            else
            {
                byte[] ack = Wire.HelloAck(NextSeq(), ProtocolConst.AckNeedsPairing, isFinal: false);
                _discoveryClient?.Send(ack, ack.Length, remote);
                LogMessage?.Invoke($"Device '{hello.Name}' requires pairing. Sent HELLO_ACK (Needs Pairing).");
            }
        }

        private void HandlePairRequest(Frame frame, IPEndPoint remote)
        {
            if (frame.Payload.Length == 0) return;
            string code = Encoding.UTF8.GetString(frame.Payload).Trim();
            string remoteIp = remote.Address.ToString();

            bool valid = _deviceManager.ValidatePairingCode(code);
            LogMessage?.Invoke($"Received PAIR_REQUEST from {remoteIp}. Valid: {valid}");

            byte[] pairResult = Wire.PairResult(NextSeq(), valid);
            _discoveryClient?.Send(pairResult, pairResult.Length, remote);

            if (valid)
            {
                _peerInfo.TryGetValue(remoteIp, out var info);
                string deviceId = info.DeviceId ?? remoteIp;
                string name = info.Name ?? "Android Tablet";

                string authMethod = (!string.IsNullOrEmpty(_deviceManager.ConfiguredPassword) && string.Equals(code, _deviceManager.ConfiguredPassword, StringComparison.Ordinal)) ? "Password" : "PIN";
                _deviceManager.AuthorizeDevice(deviceId, name, remoteIp, authMethod);
                byte[] ack = Wire.HelloAck(NextSeq(), ProtocolConst.AckAuthorized, isFinal: true);
                _discoveryClient?.Send(ack, ack.Length, remote);
                LogMessage?.Invoke($"Successfully paired and added '{name}' to known hosts via {authMethod}! Sent HELLO_ACK (Authorized).");
            }
        }

        private async Task HandleGoogleAuthAsync(Frame frame, IPEndPoint remote)
        {
            var auth = GoogleAuthPayload.Parse(frame.Payload);
            if (auth == null) return;

            string remoteIp = remote.Address.ToString();
            LogMessage?.Invoke($"Received GOOGLE_AUTH assertion from {remoteIp} (Asserted email: {auth.Email})");

            bool verified = false;
            if (_googleAuth.IsSignedIn)
            {
                verified = await _googleAuth.VerifyDeviceTokenAsync(auth.AccessToken, auth.Email);
            }
            else
            {
                // Verify directly with Google's tokeninfo endpoint to authenticate the Google identity into known hosts
                verified = await _googleAuth.VerifyTokenOnlyAsync(auth.AccessToken, auth.Email);
            }

            if (verified)
            {
                _peerInfo.TryGetValue(remoteIp, out var info);
                string deviceId = info.DeviceId ?? remoteIp;
                string name = info.Name ?? $"Android ({auth.Email})";

                _deviceManager.AuthorizeDevice(deviceId, name, remoteIp, "Google");
                byte[] ack = Wire.HelloAck(NextSeq(), ProtocolConst.AckAuthorized, isFinal: true);
                _discoveryClient?.Send(ack, ack.Length, remote);
                LogMessage?.Invoke($"GOOGLE_AUTH SUCCESS! Added '{name}' ({auth.Email}) to known hosts and authorized. Sent HELLO_ACK (Authorized).");
            }
            else
            {
                LogMessage?.Invoke($"GOOGLE_AUTH FAILED for email '{auth.Email}'. Token invalid or account mismatch.");
            }
        }

        #endregion

        #region Data Channel (UDP 41174)

        private async Task DataReceiveLoopAsync(UdpClient client, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var result = await client.ReceiveAsync(token);
                    var frame = Wire.Decode(result.Buffer);
                    if (frame == null) continue;

                    var remote = result.RemoteEndPoint;

                    switch (frame.Type)
                    {
                        case ProtocolConst.TypePenEvent:
                            string ipStr = remote.Address.ToString();
                            if (_peerInfo.TryGetValue(ipStr, out var devInfo) && _deviceManager.IsAuthorized(devInfo.DeviceId))
                            {
                                var pen = PenEventPayload.Parse(frame.Payload);
                                if (pen != null)
                                {
                                    _inputInjector.ProcessPenEvent(pen);
                                }
                            }
                            else
                            {
                                // Unauthorized or revoked device — reject and notify client
                                byte[] bye = Wire.Bye(NextSeq());
                                await client.SendAsync(bye, bye.Length, remote);
                            }
                            break;

                        case ProtocolConst.TypePing:
                            // Reply PONG with matching seq and FLAG_ACK
                            byte[] pong = Wire.Pong(frame.Seq);
                            await client.SendAsync(pong, pong.Length, remote);
                            break;

                        case ProtocolConst.TypeBye:
                            string ip = remote.Address.ToString();
                            if (_peerInfo.TryGetValue(ip, out var info))
                            {
                                _deviceManager.SetDeviceDisconnected(info.DeviceId);
                            }
                            break;
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    Debug.WriteLine($"Data recv error: {ex.Message}");
                }
            }
        }

        #endregion

        #region USB TCP Channel (127.0.0.1:41174)

        private async Task TcpAcceptLoopAsync(TcpListener listener, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var client = await listener.AcceptTcpClientAsync(token);
                    client.NoDelay = true;
                    _ = HandleTcpClientAsync(client, token);
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    Debug.WriteLine($"TCP accept error: {ex.Message}");
                }
            }
        }

        private async Task HandleTcpClientAsync(TcpClient client, CancellationToken token)
        {
            using (client)
            using (var stream = client.GetStream())
            {
                LogMessage?.Invoke("USB Android client connected over TCP (adb reverse)!");
                byte[] headerBuffer = new byte[ProtocolConst.HeaderSize];
                string usbIp = "usb";

                while (!token.IsCancellationRequested && client.Connected)
                {
                    try
                    {
                        // Read header
                        int read = 0;
                        while (read < ProtocolConst.HeaderSize)
                        {
                            int n = await stream.ReadAsync(headerBuffer.AsMemory(read, ProtocolConst.HeaderSize - read), token);
                            if (n <= 0) return;
                            read += n;
                        }

                        if (headerBuffer[0] != (byte)'D' || headerBuffer[1] != (byte)'A' ||
                            headerBuffer[2] != (byte)'S' || headerBuffer[3] != (byte)'H')
                        {
                            continue;
                        }

                        byte type = headerBuffer[4];
                        byte flags = headerBuffer[5];
                        ushort seq = BitConverter.ToUInt16(headerBuffer, 6);
                        ushort len = BitConverter.ToUInt16(headerBuffer, 8);

                        byte[] payload = new byte[len];
                        if (len > 0)
                        {
                            read = 0;
                            while (read < len)
                            {
                                int n = await stream.ReadAsync(payload.AsMemory(read, len - read), token);
                                if (n <= 0) return;
                                read += n;
                            }
                        }

                        var frame = new Frame(type, flags, seq, payload);

                        switch (type)
                        {
                            case ProtocolConst.TypeHello:
                                var hello = HelloPayload.Parse(payload);
                                if (hello != null)
                                {
                                    _peerInfo[usbIp] = (hello.DeviceId, hello.Name);
                                    bool auth = _deviceManager.IsAuthorized(hello.DeviceId);
                                    byte[] ack = Wire.HelloAck(NextSeq(), auth ? ProtocolConst.AckAuthorized : ProtocolConst.AckNeedsPairing, isFinal: auth);
                                    await stream.WriteAsync(ack, 0, ack.Length, token);
                                    await stream.FlushAsync(token);
                                }
                                break;

                            case ProtocolConst.TypePairRequest:
                                if (payload.Length > 0)
                                {
                                    string code = Encoding.UTF8.GetString(payload).Trim();
                                    bool valid = _deviceManager.ValidatePairingCode(code);
                                    byte[] pRes = Wire.PairResult(NextSeq(), valid);
                                    await stream.WriteAsync(pRes, 0, pRes.Length, token);

                                    if (valid)
                                    {
                                        _peerInfo.TryGetValue(usbIp, out var inf);
                                        string devId = inf.DeviceId ?? "usb_device";
                                        string authMethod = (!string.IsNullOrEmpty(_deviceManager.ConfiguredPassword) && string.Equals(code, _deviceManager.ConfiguredPassword, StringComparison.Ordinal)) ? "Password" : "PIN";
                                        _deviceManager.AuthorizeDevice(devId, inf.Name ?? "USB Tablet", "USB", authMethod);
                                        byte[] ack = Wire.HelloAck(NextSeq(), ProtocolConst.AckAuthorized, isFinal: true);
                                        await stream.WriteAsync(ack, 0, ack.Length, token);
                                    }
                                    await stream.FlushAsync(token);
                                }
                                break;

                            case ProtocolConst.TypeGoogleAuth:
                                var gAuth = GoogleAuthPayload.Parse(payload);
                                if (gAuth != null && _googleAuth.IsSignedIn)
                                {
                                    bool verified = await _googleAuth.VerifyDeviceTokenAsync(gAuth.AccessToken, gAuth.Email);
                                    if (verified)
                                    {
                                        _peerInfo.TryGetValue(usbIp, out var inf);
                                        string devId = inf.DeviceId ?? "usb_device";
                                        _deviceManager.AuthorizeDevice(devId, inf.Name ?? "USB Tablet", "USB", "Google");
                                        byte[] ack = Wire.HelloAck(NextSeq(), ProtocolConst.AckAuthorized, isFinal: true);
                                        await stream.WriteAsync(ack, 0, ack.Length, token);
                                        await stream.FlushAsync(token);
                                        LogMessage?.Invoke($"USB: Auto-authorized device for '{gAuth.Email}' via Google!");
                                    }
                                }
                                break;

                            case ProtocolConst.TypePenEvent:
                                _peerInfo.TryGetValue(usbIp, out var usbInf);
                                if (usbInf.DeviceId != null && _deviceManager.IsAuthorized(usbInf.DeviceId))
                                {
                                    var pen = PenEventPayload.Parse(payload);
                                    if (pen != null)
                                    {
                                        _inputInjector.ProcessPenEvent(pen);
                                    }
                                }
                                else
                                {
                                    byte[] bye = Wire.Bye(NextSeq());
                                    await stream.WriteAsync(bye, 0, bye.Length, token);
                                    await stream.FlushAsync(token);
                                    return;
                                }
                                break;

                            case ProtocolConst.TypePing:
                                byte[] pong = Wire.Pong(seq);
                                await stream.WriteAsync(pong, 0, pong.Length, token);
                                await stream.FlushAsync(token);
                                break;

                            case ProtocolConst.TypeBye:
                                return;
                        }
                    }
                    catch (OperationCanceledException) { break; }
                    catch (Exception ex)
                    {
                        Debug.WriteLine($"TCP stream error: {ex.Message}");
                        break;
                    }
                }
            }
        }

        #endregion

        public void Dispose()
        {
            Stop();
        }
    }
}
