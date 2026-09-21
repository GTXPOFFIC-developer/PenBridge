using System;
using System.Threading;
using System.Threading.Tasks;
using DashboardHost.Core;

namespace DashboardHost.Linux
{
    internal class Program
    {
        private static async Task Main(string[] args)
        {
            Console.ForegroundColor = ConsoleColor.Magenta;
            Console.WriteLine("╔════════════════════════════════════════════════════╗");
            Console.WriteLine("║            DASHBOARD LINUX HOST v1.0               ║");
            Console.WriteLine("║   Android Tablet Pen & Touch Bridge for Linux      ║");
            Console.WriteLine("╚════════════════════════════════════════════════════╝");
            Console.ResetColor();

            var deviceManager = new DeviceManager();
            var googleAuth = new GoogleAuthService();
            using var injector = new LinuxInputInjector();

            Console.WriteLine($"\n[INFO] 6-Digit Pairing PIN: {deviceManager.CurrentPairingCode}");

            if (googleAuth.IsSignedIn)
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine($"[AUTH] Signed in as: {googleAuth.CurrentAccount?.Email} (Auto-pair active)");
                Console.ResetColor();
            }
            else
            {
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine("[AUTH] Not signed in to Google. Run with --login to authenticate, or use PIN pairing.");
                Console.ResetColor();
            }

            if (args.Length > 0 && args[0] == "--login")
            {
                Console.WriteLine("[AUTH] Launching browser for Google OAuth sign-in...");
                var account = await googleAuth.SignInAsync();
                if (account != null)
                {
                    Console.ForegroundColor = ConsoleColor.Green;
                    Console.WriteLine($"[AUTH] Successfully signed in as {account.Email}!");
                    Console.ResetColor();
                }
                else
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine("[AUTH] Sign-in failed or timed out.");
                    Console.ResetColor();
                }
            }

            var cts = new CancellationTokenSource();
            Console.CancelKeyPress += (s, e) =>
            {
                e.Cancel = true;
                cts.Cancel();
            };

            Console.WriteLine("\n[SERVER] Listening on UDP 41173 (Discovery), UDP 41174 (Data), and TCP 41174 (USB)...");
            Console.WriteLine("[SERVER] Press Ctrl+C to exit.\n");

            // Setup USB reverse if adb available
            var (adbSuccess, adbMsg) = await AdbHelper.SetupReverseAsync();
            if (adbSuccess)
            {
                Console.ForegroundColor = ConsoleColor.Cyan;
                Console.WriteLine($"[ADB] {adbMsg}");
                Console.ResetColor();
            }

            try
            {
                await Task.Delay(Timeout.Infinite, cts.Token);
            }
            catch (OperationCanceledException)
            {
                Console.WriteLine("\n[SERVER] Shutting down...");
            }
        }
    }
}
