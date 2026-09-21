using System;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace DashboardHost.Core
{
    /// <summary>
    /// Advertises the Dashboard Host as a "_dashboard._tcp" mDNS service so
    /// Android / iOS clients can discover it without UDP broadcast.
    ///
    /// Uses raw UDP multicast to the mDNS group (224.0.0.251:5353) — no
    /// third-party library needed. Sends a minimal DNS response packet that
    /// announces a PTR + SRV + A record for "_dashboard._tcp.local".
    /// </summary>
    public sealed class MdnsAdvertiser : IDisposable
    {
        private const string ServiceType = "_dashboard._tcp.local.";
        private const string ServiceName = "Dashboard Host";
        private const int MdnsPort = 5353;
        private static readonly IPAddress MdnsGroup = IPAddress.Parse("224.0.0.251");

        private readonly int _port;
        private readonly string _hostName;
        private CancellationTokenSource? _cts;
        private UdpClient? _client;

        public MdnsAdvertiser(string hostName, int port)
        {
            _hostName = hostName.Replace(' ', '-');
            _port = port;
        }

        public void Start()
        {
            if (_cts != null) return;
            _cts = new CancellationTokenSource();
            _client = CreateMdnsSocket();
            var token = _cts.Token;
            Task.Run(() => AdvertiseLoopAsync(token), token);
            Task.Run(() => QueryReceiveLoopAsync(token), token);
        }

        public void Stop()
        {
            try { _cts?.Cancel(); } catch { }
            try { _client?.Close(); } catch { }
            _cts = null;
            _client = null;
        }

        public void Dispose() => Stop();

        private UdpClient CreateMdnsSocket()
        {
            var client = new UdpClient();
            client.Client.ExclusiveAddressUse = false;
            client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            client.Client.Bind(new IPEndPoint(IPAddress.Any, MdnsPort));
            client.Ttl = 255;

            // Join multicast group on default and on all active interfaces
            try { client.JoinMulticastGroup(MdnsGroup); } catch { }

            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus == OperationalStatus.Up &&
                    nic.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
                    nic.SupportsMulticast)
                {
                    var ipProps = nic.GetIPProperties();
                    var ipv4Props = ipProps.GetIPv4Properties();
                    if (ipv4Props != null)
                    {
                        try
                        {
                            client.Client.SetSocketOption(
                                SocketOptionLevel.IP,
                                SocketOptionName.AddMembership,
                                new MulticastOption(MdnsGroup, ipv4Props.Index));
                        }
                        catch { }
                    }
                }
            }

            return client;
        }

        private async Task QueryReceiveLoopAsync(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested && _client != null)
            {
                try
                {
                    var result = await _client.ReceiveAsync(ct);
                    // Check if it's an mDNS query (QR bit = 0 in flags at bytes 2-3)
                    if (result.Buffer.Length >= 12)
                    {
                        byte flagsHigh = result.Buffer[2];
                        bool isQuery = (flagsHigh & 0x80) == 0;
                        if (isQuery)
                        {
                            var packet = BuildMdnsResponse();
                            var multicastEp = new IPEndPoint(MdnsGroup, MdnsPort);
                            await _client.SendAsync(packet, packet.Length, multicastEp);
                            if (!result.RemoteEndPoint.Address.Equals(IPAddress.Any))
                            {
                                await _client.SendAsync(packet, packet.Length, result.RemoteEndPoint);
                            }
                        }
                    }
                }
                catch (OperationCanceledException) { break; }
                catch { /* transient socket errors */ }
            }
        }

        private async Task AdvertiseLoopAsync(CancellationToken ct)
        {
            // Initial rapid announcement burst (0s, 0.5s, 1s, 2s)
            int[] initialDelays = { 500, 1000, 2000 };
            foreach (var delay in initialDelays)
            {
                if (ct.IsCancellationRequested) return;
                try
                {
                    var packet = BuildMdnsResponse();
                    var ep = new IPEndPoint(MdnsGroup, MdnsPort);
                    await (_client?.SendAsync(packet, packet.Length, ep) ?? Task.CompletedTask);
                }
                catch { }

                try { await Task.Delay(delay, ct); }
                catch (OperationCanceledException) { return; }
            }

            // Steady-state: announce every 3 seconds so clients discover immediately
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var packet = BuildMdnsResponse();
                    var ep = new IPEndPoint(MdnsGroup, MdnsPort);
                    await (_client?.SendAsync(packet, packet.Length, ep) ?? Task.CompletedTask);
                }
                catch (OperationCanceledException) { break; }
                catch { /* network down transiently — retry */ }

                try { await Task.Delay(3000, ct); }
                catch (OperationCanceledException) { break; }
            }
        }

        /// <summary>
        /// Builds a minimal multicast DNS response advertising:
        ///   PTR  _dashboard._tcp.local.  →  Dashboard-Host._dashboard._tcp.local.
        ///   SRV  Dashboard-Host._dashboard._tcp.local.  →  hostname.local. : port
        ///   A    hostname.local.  →  first non-loopback IPv4
        /// </summary>
        private byte[] BuildMdnsResponse()
        {
            var fqdn = $"{_hostName}._dashboard._tcp.local.";
            var hostFqdn = $"{_hostName}.local.";
            var myIp = GetLocalIp();

            using var ms = new System.IO.MemoryStream(256);
            using var bw = new System.IO.BinaryWriter(ms);

            // DNS Header (ID=0, QR=1/Response, AA=1/Authoritative, ANCOUNT=3)
            WriteUInt16(bw, 0x0000); // ID
            WriteUInt16(bw, 0x8400); // Flags: Response | Authoritative
            WriteUInt16(bw, 0);      // QDCOUNT
            WriteUInt16(bw, 3);      // ANCOUNT
            WriteUInt16(bw, 0);      // NSCOUNT
            WriteUInt16(bw, 0);      // ARCOUNT

            // --- PTR record: _dashboard._tcp.local. → fqdn (TTL 4500 s) ---
            WriteDnsName(bw, ServiceType);
            WriteUInt16(bw, 12);         // TYPE PTR
            WriteUInt16(bw, 0x8001);     // CLASS IN + cache-flush
            WriteUInt32(bw, 4500);       // TTL
            var ptrRdata = EncodeDnsName(fqdn);
            WriteUInt16(bw, (ushort)ptrRdata.Length);
            bw.Write(ptrRdata);

            // --- SRV record: fqdn → hostFqdn:port (TTL 120 s) ---
            WriteDnsName(bw, fqdn);
            WriteUInt16(bw, 33);         // TYPE SRV
            WriteUInt16(bw, 0x8001);     // CLASS IN + cache-flush
            WriteUInt32(bw, 120);        // TTL
            var targetEncoded = EncodeDnsName(hostFqdn);
            WriteUInt16(bw, (ushort)(6 + targetEncoded.Length));
            WriteUInt16(bw, 0);          // priority
            WriteUInt16(bw, 0);          // weight
            WriteUInt16(bw, (ushort)_port);
            bw.Write(targetEncoded);

            // --- A record: hostFqdn → IP (TTL 120 s) ---
            WriteDnsName(bw, hostFqdn);
            WriteUInt16(bw, 1);          // TYPE A
            WriteUInt16(bw, 0x8001);     // CLASS IN + cache-flush
            WriteUInt32(bw, 120);        // TTL
            WriteUInt16(bw, 4);          // RDLENGTH
            bw.Write(myIp.GetAddressBytes());

            return ms.ToArray();
        }

        private static void WriteUInt16(System.IO.BinaryWriter bw, ushort v)
        {
            bw.Write((byte)(v >> 8));
            bw.Write((byte)(v & 0xFF));
        }
        private static void WriteUInt32(System.IO.BinaryWriter bw, uint v)
        {
            bw.Write((byte)(v >> 24)); bw.Write((byte)(v >> 16));
            bw.Write((byte)(v >> 8));  bw.Write((byte)(v & 0xFF));
        }

        private static void WriteDnsName(System.IO.BinaryWriter bw, string fqdn)
            => bw.Write(EncodeDnsName(fqdn));

        private static byte[] EncodeDnsName(string fqdn)
        {
            var parts = fqdn.TrimEnd('.').Split('.');
            using var ms = new System.IO.MemoryStream();
            foreach (var part in parts)
            {
                var bytes = Encoding.ASCII.GetBytes(part);
                ms.WriteByte((byte)bytes.Length);
                ms.Write(bytes, 0, bytes.Length);
            }
            ms.WriteByte(0); // root label
            return ms.ToArray();
        }

        private static IPAddress GetLocalIp()
        {
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                        continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily == AddressFamily.InterNetwork &&
                            !IPAddress.IsLoopback(ua.Address))
                            return ua.Address;
                    }
                }
            }
            catch { }
            return IPAddress.Loopback;
        }
    }
}
