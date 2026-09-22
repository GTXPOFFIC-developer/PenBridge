using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace DashboardHost.Core
{
    public sealed class GoogleAccountInfo
    {
        public string Email { get; set; } = string.Empty;
        public string DisplayName { get; set; } = string.Empty;
        public string PictureUrl { get; set; } = string.Empty;
        public string AccessToken { get; set; } = string.Empty;
        public string RefreshToken { get; set; } = string.Empty;
        public long ExpiresAt { get; set; }
    }

    public sealed class GoogleAuthService
    {
        public static string ClientId { get; set; } = Environment.GetEnvironmentVariable("GOOGLE_CLIENT_ID") ?? "YOUR_GOOGLE_CLIENT_ID_HERE";
        public static string ClientSecret { get; set; } = Environment.GetEnvironmentVariable("GOOGLE_CLIENT_SECRET") ?? "YOUR_GOOGLE_CLIENT_SECRET_HERE";
        public const int LoopbackPort = 28314;
        public static readonly string RedirectUri = $"http://localhost:{LoopbackPort}/";

        private static readonly HttpClient HttpClient = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        private readonly string _storagePath;

        public GoogleAccountInfo? CurrentAccount { get; private set; }
        public bool IsSignedIn => CurrentAccount != null && !string.IsNullOrWhiteSpace(CurrentAccount.Email);

        public event Action<GoogleAccountInfo?>? AccountChanged;

        public GoogleAuthService()
        {
            string appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DashboardHost");
            Directory.CreateDirectory(appData);
            _storagePath = Path.Combine(appData, "google_auth.json");
            LoadSavedAccount();
        }

        private void LoadSavedAccount()
        {
            try
            {
                if (File.Exists(_storagePath))
                {
                    string json = File.ReadAllText(_storagePath);
                    CurrentAccount = JsonSerializer.Deserialize<GoogleAccountInfo>(json);
                }
            }
            catch
            {
                CurrentAccount = null;
            }
        }

        private void SaveAccount()
        {
            try
            {
                if (CurrentAccount != null)
                {
                    string json = JsonSerializer.Serialize(CurrentAccount, new JsonSerializerOptions { WriteIndented = true });
                    File.WriteAllText(_storagePath, json);
                }
                else if (File.Exists(_storagePath))
                {
                    File.Delete(_storagePath);
                }
            }
            catch
            {
            }
        }

        public async Task<GoogleAccountInfo?> SignInAsync()
        {
            string verifier = GenerateRandomBase64Url(48);
            string challenge = GenerateCodeChallenge(verifier);
            string state = GenerateRandomBase64Url(16);

            string authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                $"?client_id={Uri.EscapeDataString(ClientId)}" +
                $"&redirect_uri={Uri.EscapeDataString(RedirectUri)}" +
                $"&response_type=code" +
                $"&scope={Uri.EscapeDataString("openid email profile")}" +
                $"&code_challenge={challenge}" +
                $"&code_challenge_method=S256" +
                $"&state={state}" +
                $"&prompt=select_account";

            using var listener = new HttpListener();
            listener.Prefixes.Add(RedirectUri);
            try
            {
                listener.Start();
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to start HttpListener on {RedirectUri}: {ex.Message}");
                return null;
            }

            // Launch default browser
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = authUrl,
                    UseShellExecute = true
                });
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to open browser: {ex.Message}");
                listener.Stop();
                return null;
            }

            // Listen for OAuth callback
            string? authCode = null;
            try
            {
                var contextTask = listener.GetContextAsync();
                var completedTask = await Task.WhenAny(contextTask, Task.Delay(TimeSpan.FromMinutes(3)));
                if (completedTask != contextTask)
                {
                    listener.Stop();
                    return null; // Timeout
                }

                var context = await contextTask;
                var request = context.Request;
                var response = context.Response;

                string? receivedState = request.QueryString["state"];
                string? code = request.QueryString["code"];

                // Respond to browser with friendly HTML
                string responseHtml = @"<!doctype html>
<html>
<head><meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>Dashboard Host</title></head>
<body style='background:#0B0E1A;color:#ECEEFF;font-family:Segoe UI,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>
<div style='text-align:center;padding:32px;background:#12162B;border-radius:16px;border:1px solid rgba(255,255,255,0.15);box-shadow:0 8px 32px rgba(0,0,0,0.5)'>
<h1 style='margin:0 0 12px;color:#8B5CF6'>Dashboard</h1>
<p style='font-size:18px;margin:0 0 8px'>Connected to your Google Account!</p>
<p style='color:#8B93B8;margin:0'>You can close this tab and return to the Dashboard app.</p>
</div>
</body>
</html>";
                byte[] responseBytes = Encoding.UTF8.GetBytes(responseHtml);
                response.ContentType = "text/html; charset=utf-8";
                response.ContentLength64 = responseBytes.Length;
                await response.OutputStream.WriteAsync(responseBytes, 0, responseBytes.Length);
                response.OutputStream.Close();

                if (receivedState == state && !string.IsNullOrEmpty(code))
                {
                    authCode = code;
                }
            }
            finally
            {
                try { listener.Stop(); } catch { }
            }

            if (string.IsNullOrEmpty(authCode))
                return null;

            // Exchange code for tokens
            try
            {
                var tokenReq = new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    ["code"] = authCode,
                    ["client_id"] = ClientId,
                    ["client_secret"] = ClientSecret,
                    ["redirect_uri"] = RedirectUri,
                    ["grant_type"] = "authorization_code",
                    ["code_verifier"] = verifier
                });

                var tokenResp = await HttpClient.PostAsync("https://oauth2.googleapis.com/token", tokenReq);
                if (!tokenResp.IsSuccessStatusCode) return null;

                string tokenJson = await tokenResp.Content.ReadAsStringAsync();
                using var doc = JsonDocument.Parse(tokenJson);
                var root = doc.RootElement;

                string accessToken = root.GetProperty("access_token").GetString() ?? "";
                string refreshToken = root.TryGetProperty("refresh_token", out var rt) ? rt.GetString() ?? "" : "";
                string idToken = root.TryGetProperty("id_token", out var it) ? it.GetString() ?? "" : "";

                // Get profile
                string email = "";
                string name = "";
                string picture = "";

                if (!string.IsNullOrEmpty(idToken))
                {
                    var profResp = await HttpClient.GetAsync($"https://oauth2.googleapis.com/tokeninfo?id_token={Uri.EscapeDataString(idToken)}");
                    if (profResp.IsSuccessStatusCode)
                    {
                        string profJson = await profResp.Content.ReadAsStringAsync();
                        using var profDoc = JsonDocument.Parse(profJson);
                        var pRoot = profDoc.RootElement;
                        email = pRoot.TryGetProperty("email", out var em) ? em.GetString() ?? "" : "";
                        name = pRoot.TryGetProperty("name", out var nm) ? nm.GetString() ?? "" : "";
                        picture = pRoot.TryGetProperty("picture", out var pic) ? pic.GetString() ?? "" : "";
                    }
                }

                if (string.IsNullOrEmpty(email)) return null;

                CurrentAccount = new GoogleAccountInfo
                {
                    Email = email,
                    DisplayName = string.IsNullOrWhiteSpace(name) ? email.Split('@')[0] : name,
                    PictureUrl = picture,
                    AccessToken = accessToken,
                    RefreshToken = refreshToken,
                    ExpiresAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 3600000
                };

                SaveAccount();
                AccountChanged?.Invoke(CurrentAccount);
                return CurrentAccount;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Token exchange error: {ex.Message}");
                return null;
            }
        }

        public void SignOut()
        {
            CurrentAccount = null;
            SaveAccount();
            AccountChanged?.Invoke(null);
        }

        public async Task<bool> VerifyDeviceTokenAsync(string accessToken, string assertedEmail)
        {
            if (string.IsNullOrWhiteSpace(accessToken) || string.IsNullOrWhiteSpace(assertedEmail))
                return false;

            if (CurrentAccount == null || string.IsNullOrWhiteSpace(CurrentAccount.Email))
                return false;

            try
            {
                // Call Google's free tokeninfo endpoint
                var resp = await HttpClient.GetAsync($"https://oauth2.googleapis.com/tokeninfo?access_token={Uri.EscapeDataString(accessToken)}");
                if (!resp.IsSuccessStatusCode) return false;

                string json = await resp.Content.ReadAsStringAsync();
                using var doc = JsonDocument.Parse(json);
                if (doc.RootElement.TryGetProperty("email", out var emailProp))
                {
                    string verifiedEmail = emailProp.GetString() ?? "";
                    // Auto-authorize if the verified token matches the asserted email AND the Windows host account!
                    if (string.Equals(verifiedEmail, assertedEmail, StringComparison.OrdinalIgnoreCase) &&
                        string.Equals(verifiedEmail, CurrentAccount.Email, StringComparison.OrdinalIgnoreCase))
                    {
                        return true;
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"VerifyDeviceToken error: {ex.Message}");
            }

            return false;
        }

        public async Task<bool> VerifyTokenOnlyAsync(string accessToken, string assertedEmail)
        {
            if (string.IsNullOrWhiteSpace(accessToken) || string.IsNullOrWhiteSpace(assertedEmail))
                return false;

            try
            {
                var resp = await HttpClient.GetAsync($"https://oauth2.googleapis.com/tokeninfo?access_token={Uri.EscapeDataString(accessToken)}");
                if (!resp.IsSuccessStatusCode) return false;

                string json = await resp.Content.ReadAsStringAsync();
                using var doc = JsonDocument.Parse(json);
                if (doc.RootElement.TryGetProperty("email", out var emailProp))
                {
                    string verifiedEmail = emailProp.GetString() ?? "";
                    if (string.Equals(verifiedEmail, assertedEmail, StringComparison.OrdinalIgnoreCase))
                    {
                        return true;
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"VerifyTokenOnly error: {ex.Message}");
            }

            return false;
        }

        private static string GenerateRandomBase64Url(int byteCount)
        {
            byte[] bytes = new byte[byteCount];
            RandomNumberGenerator.Fill(bytes);
            return Convert.ToBase64String(bytes)
                .Replace("+", "-")
                .Replace("/", "_")
                .Replace("=", "");
        }

        private static string GenerateCodeChallenge(string verifier)
        {
            byte[] bytes = Encoding.ASCII.GetBytes(verifier);
            byte[] hash = SHA256.HashData(bytes);
            return Convert.ToBase64String(hash)
                .Replace("+", "-")
                .Replace("/", "_")
                .Replace("=", "");
        }
    }
}
