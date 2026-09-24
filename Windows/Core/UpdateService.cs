using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;

namespace DashboardHost.Core
{
    public sealed class UpdateService
    {
        private const string RepoOwner = "GTXPOFFIC-developer";
        private const string RepoName = "PenBridge";
        private readonly HttpClient _http;

        public static string CurrentVersion => "1.3.0";

        public event Action<string>? LogMessage;
        public event Action<bool, string?, string?>? UpdateChecked; // (hasUpdate, latestVersion, downloadUrl)

        public UpdateService()
        {
            _http = new HttpClient();
            _http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("PenBridge-Host", CurrentVersion));
        }

        public async Task<(bool HasUpdate, string? LatestVersion, string? DownloadUrl)> CheckForUpdatesAsync()
        {
            try
            {
                string url = $"https://api.github.com/repos/{RepoOwner}/{RepoName}/releases/latest";
                var response = await _http.GetStringAsync(url);
                using var doc = JsonDocument.Parse(response);
                var root = doc.RootElement;

                string tagName = root.GetProperty("tag_name").GetString() ?? "";
                string latestVersion = tagName.TrimStart('v', 'V');

                if (string.IsNullOrWhiteSpace(latestVersion))
                {
                    UpdateChecked?.Invoke(false, null, null);
                    return (false, null, null);
                }

                // Check assets for Windows portable zip or standalone binary
                string? downloadUrl = null;
                if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
                {
                    bool is64 = Environment.Is64BitOperatingSystem;
                    string targetPattern = is64 ? "Windows-x64" : "Windows-x86";

                    foreach (var asset in assets.EnumerateArray())
                    {
                        string name = asset.GetProperty("name").GetString() ?? "";
                        if (name.Contains(targetPattern, StringComparison.OrdinalIgnoreCase) &&
                            (name.EndsWith("-portable.zip", StringComparison.OrdinalIgnoreCase) ||
                             name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)))
                        {
                            downloadUrl = asset.GetProperty("browser_download_url").GetString();
                            break;
                        }
                    }
                }

                bool hasUpdate = IsVersionNewer(latestVersion, CurrentVersion);
                UpdateChecked?.Invoke(hasUpdate, latestVersion, downloadUrl);
                return (hasUpdate, latestVersion, downloadUrl);
            }
            catch (Exception ex)
            {
                LogMessage?.Invoke($"Update check failed: {ex.Message}");
                UpdateChecked?.Invoke(false, null, null);
                return (false, null, null);
            }
        }

        public async Task ApplyUpdateAsync(string downloadUrl, Action<int>? progressCallback = null)
        {
            string appDir = AppDomain.CurrentDomain.BaseDirectory;
            string currentExe = Process.GetCurrentProcess().MainModule?.FileName ?? Path.Combine(appDir, "DashboardHost.exe");
            string updateExe = Path.Combine(appDir, "DashboardHost.update.exe");
            string scriptPath = Path.Combine(appDir, "update_runner.cmd");

            LogMessage?.Invoke("Downloading update package...");

            using (var response = await _http.GetAsync(downloadUrl, HttpCompletionOption.ResponseHeadersRead))
            {
                response.EnsureSuccessStatusCode();
                var totalBytes = response.Content.Headers.ContentLength ?? -1L;

                using (var stream = await response.Content.ReadAsStreamAsync())
                using (var fs = new FileStream(updateExe, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    var buffer = new byte[81920];
                    long totalRead = 0;
                    int bytesRead;

                    while ((bytesRead = await stream.ReadAsync(buffer, 0, buffer.Length)) > 0)
                    {
                        await fs.WriteAsync(buffer, 0, bytesRead);
                        totalRead += bytesRead;

                        if (totalBytes > 0 && progressCallback != null)
                        {
                            int pct = (int)((totalRead * 100) / totalBytes);
                            progressCallback(pct);
                        }
                    }
                }
            }

            LogMessage?.Invoke("Update downloaded. Restarting application seamlessly without installer...");

            // Create batch self-replacer script that replaces the executable and restarts
            string script = $@"@echo off
timeout /t 1 /nobreak > nul
:retry
del ""{currentExe}"" > nul 2>&1
if exist ""{currentExe}"" (
    timeout /t 1 /nobreak > nul
    goto retry
)
move /y ""{updateExe}"" ""{currentExe}"" > nul
start """" ""{currentExe}""
del ""%~f0""
";
            await File.WriteAllTextAsync(scriptPath, script);

            var psi = new ProcessStartInfo
            {
                FileName = scriptPath,
                CreateNoWindow = true,
                UseShellExecute = false
            };
            Process.Start(psi);

            Application.Current.Dispatcher.Invoke(() =>
            {
                Application.Current.Shutdown();
            });
        }

        private static bool IsVersionNewer(string remote, string local)
        {
            if (Version.TryParse(remote, out var vRemote) && Version.TryParse(local, out var vLocal))
            {
                return vRemote > vLocal;
            }
            return !string.Equals(remote, local, StringComparison.OrdinalIgnoreCase);
        }
    }
}
