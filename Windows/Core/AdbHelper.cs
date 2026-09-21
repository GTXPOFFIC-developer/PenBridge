using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace DashboardHost.Core
{
    public enum AdbStatus
    {
        Success,
        DeviceUnauthorized,
        NoDevice,
        NotFound,
        Error
    }

    public sealed class AdbResult
    {
        public AdbStatus Status { get; init; }
        public bool Success => Status == AdbStatus.Success;
        public string Message { get; init; } = "";
        public string? Guidance { get; init; }
    }

    public static class AdbHelper
    {
        public static string? FindAdbPath()
        {
            // 0. Bundled in app
            string baseDir = AppContext.BaseDirectory;
            foreach (var rel in new[] { Path.Combine("adb", "adb.exe"), "adb.exe" })
            {
                string c = Path.Combine(baseDir, rel);
                if (File.Exists(c)) return c;
            }

            // 1. PATH
            string? pathEnv = Environment.GetEnvironmentVariable("PATH");
            if (pathEnv != null)
            {
                foreach (string dir in pathEnv.Split(Path.PathSeparator))
                {
                    try
                    {
                        string c = Path.Combine(dir.Trim(), "adb.exe");
                        if (File.Exists(c)) return c;
                    }
                    catch { }
                }
            }

            // 2. Common SDK locations
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string[] candidates =
            {
                Path.Combine(local, "Android", "Sdk", "platform-tools", "adb.exe"),
                @"C:\Android\platform-tools\adb.exe",
                @"C:\Program Files\Android\android-sdk\platform-tools\adb.exe",
            };
            foreach (var c in candidates)
                if (File.Exists(c)) return c;

            // 3. ANDROID_HOME / ANDROID_SDK_ROOT
            foreach (var envVar in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
            {
                string? home = Environment.GetEnvironmentVariable(envVar);
                if (!string.IsNullOrEmpty(home))
                {
                    string c = Path.Combine(home, "platform-tools", "adb.exe");
                    if (File.Exists(c)) return c;
                }
            }

            return null;
        }

        private static async Task<(int ExitCode, string StdOut, string StdErr)> RunAdbAsync(string adb, string arguments)
        {
            var psi = new ProcessStartInfo
            {
                FileName = adb,
                Arguments = arguments,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };

            using var proc = Process.Start(psi);
            if (proc == null) return (-1, "", "Failed to start adb process.");

            string output = await proc.StandardOutput.ReadToEndAsync();
            string error = await proc.StandardError.ReadToEndAsync();
            await proc.WaitForExitAsync();
            return (proc.ExitCode, output.Trim(), error.Trim());
        }

        public static async Task<AdbResult> SetupReverseAsync()
        {
            string? adb = FindAdbPath();
            if (adb == null)
            {
                return new AdbResult
                {
                    Status = AdbStatus.NotFound,
                    Message = "ADB not found on this machine.",
                    Guidance = "ADB is bundled with Dashboard Host. If it was removed, reinstall from Dashboard-Setup.exe."
                };
            }

            // Step 1: adb devices to check connection state
            var (_, devOut, _) = await RunAdbAsync(adb, "devices");
            bool hasUnauthorized = devOut.Contains("unauthorized", StringComparison.OrdinalIgnoreCase);
            bool hasDevice = devOut.Contains("\tdevice");
            bool noDevice = !hasDevice && !hasUnauthorized;

            if (hasUnauthorized)
            {
                // Kill and restart the adb server to clear stale sessions
                await RunAdbAsync(adb, "kill-server");
                await Task.Delay(600);
                await RunAdbAsync(adb, "start-server");
                await Task.Delay(400);

                // Re-check
                var (_, devOut2, _) = await RunAdbAsync(adb, "devices");
                hasUnauthorized = devOut2.Contains("unauthorized", StringComparison.OrdinalIgnoreCase);
                hasDevice = devOut2.Contains("\tdevice");

                if (hasUnauthorized)
                {
                    return new AdbResult
                    {
                        Status = AdbStatus.DeviceUnauthorized,
                        Message = "Android device found but authorization is pending.",
                        Guidance = "On your Android tablet, unlock the screen and tap 'Always allow from this computer' in the USB debugging authorization dialog."
                    };
                }
            }

            if (noDevice && !hasDevice)
            {
                return new AdbResult
                {
                    Status = AdbStatus.NoDevice,
                    Message = "No Android device detected over USB.",
                    Guidance = "Connect your tablet via USB, enable USB Debugging in Developer Options, then try again."
                };
            }

            // Step 2: set up reverse
            var (exitCode, reverseOut, reverseErr) = await RunAdbAsync(adb, "reverse tcp:41174 tcp:41174");
            if (exitCode == 0)
            {
                return new AdbResult
                {
                    Status = AdbStatus.Success,
                    Message = "USB tunnel active: tablet → PC on TCP 41174.",
                    Guidance = "The Android app will now connect over USB automatically."
                };
            }

            string combined = string.IsNullOrWhiteSpace(reverseErr) ? reverseOut : reverseErr;
            if (combined.Contains("unauthorized", StringComparison.OrdinalIgnoreCase))
            {
                return new AdbResult
                {
                    Status = AdbStatus.DeviceUnauthorized,
                    Message = "Device not yet authorized.",
                    Guidance = "Unlock your tablet and tap 'Always allow from this computer' when the USB debugging dialog appears."
                };
            }

            return new AdbResult
            {
                Status = AdbStatus.Error,
                Message = combined,
                Guidance = "Make sure the tablet is unlocked, USB Debugging is enabled, and you accepted the authorization prompt."
            };
        }
    }
}
