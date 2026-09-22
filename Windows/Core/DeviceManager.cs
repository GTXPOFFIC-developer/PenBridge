using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;

namespace DashboardHost.Core
{
    public sealed class DeviceInfo
    {
        public string DeviceId { get; set; } = string.Empty;
        public string Name { get; set; } = string.Empty;
        public string Ip { get; set; } = string.Empty;
        public string AuthMethod { get; set; } = string.Empty; // "Google", "PIN", "Manual"
        public DateTime FirstAuthorized { get; set; } = DateTime.UtcNow;
        public DateTime LastSeen { get; set; } = DateTime.UtcNow;
        public bool IsConnected { get; set; }
        public int LatencyMs { get; set; }
    }

    public sealed class DeviceManager
    {
        private readonly string _storagePath;
        private readonly ConcurrentDictionary<string, DeviceInfo> _allowedDevices = new(StringComparer.OrdinalIgnoreCase);

        public string CurrentPairingCode { get; private set; } = "123456";
        public string? ConfiguredPassword { get; set; }

        public event Action? DevicesChanged;

        public DeviceManager()
        {
            string appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DashboardHost");
            Directory.CreateDirectory(appData);
            _storagePath = Path.Combine(appData, "devices.json");
            ConfiguredPassword = Environment.GetEnvironmentVariable("PENBRIDGE_PASSWORD");
            GenerateNewPairingCode();
            LoadAllowedDevices();
        }

        public void GenerateNewPairingCode()
        {
            int code = RandomNumberGenerator.GetInt32(100000, 1000000);
            CurrentPairingCode = code.ToString("D6");
        }

        public bool ValidatePairingCode(string submittedCode)
        {
            if (string.IsNullOrWhiteSpace(submittedCode)) return false;
            string trimmed = submittedCode.Trim();
            return string.Equals(CurrentPairingCode.Trim(), trimmed, StringComparison.Ordinal) ||
                   (!string.IsNullOrEmpty(ConfiguredPassword) && string.Equals(ConfiguredPassword.Trim(), trimmed, StringComparison.Ordinal));
        }

        public bool IsAuthorized(string deviceId)
        {
            if (string.IsNullOrWhiteSpace(deviceId)) return false;
            return _allowedDevices.ContainsKey(deviceId);
        }

        public void AuthorizeDevice(string deviceId, string name, string ip, string authMethod)
        {
            if (string.IsNullOrWhiteSpace(deviceId)) return;

            var device = _allowedDevices.GetOrAdd(deviceId, _ => new DeviceInfo
            {
                DeviceId = deviceId,
                Name = string.IsNullOrWhiteSpace(name) ? "Android Tablet" : name,
                Ip = ip,
                AuthMethod = authMethod,
                FirstAuthorized = DateTime.UtcNow,
                LastSeen = DateTime.UtcNow,
                IsConnected = true
            });

            device.Name = string.IsNullOrWhiteSpace(name) ? device.Name : name;
            device.Ip = ip;
            device.AuthMethod = authMethod;
            device.LastSeen = DateTime.UtcNow;
            device.IsConnected = true;

            SaveAllowedDevices();
            DevicesChanged?.Invoke();
        }

        public void UpdateDeviceActivity(string deviceId, string ip, int latencyMs = -1)
        {
            if (_allowedDevices.TryGetValue(deviceId, out var dev))
            {
                dev.LastSeen = DateTime.UtcNow;
                dev.IsConnected = true;
                dev.Ip = ip;
                if (latencyMs >= 0) dev.LatencyMs = latencyMs;
                DevicesChanged?.Invoke();
            }
        }

        public void SetDeviceDisconnected(string deviceId)
        {
            if (_allowedDevices.TryGetValue(deviceId, out var dev))
            {
                dev.IsConnected = false;
                DevicesChanged?.Invoke();
            }
        }

        public void RevokeDevice(string deviceId)
        {
            if (_allowedDevices.TryRemove(deviceId, out _))
            {
                SaveAllowedDevices();
                DevicesChanged?.Invoke();
            }
        }

        public IReadOnlyCollection<DeviceInfo> GetAllowedDevices() => _allowedDevices.Values.ToList();

        private void LoadAllowedDevices()
        {
            try
            {
                if (File.Exists(_storagePath))
                {
                    string json = File.ReadAllText(_storagePath);
                    var list = JsonSerializer.Deserialize<List<DeviceInfo>>(json);
                    if (list != null)
                    {
                        foreach (var d in list)
                        {
                            d.IsConnected = false;
                            _allowedDevices[d.DeviceId] = d;
                        }
                    }
                }
            }
            catch { }
        }

        private void SaveAllowedDevices()
        {
            try
            {
                var list = new List<DeviceInfo>(_allowedDevices.Values);
                string json = JsonSerializer.Serialize(list, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(_storagePath, json);
            }
            catch { }
        }
    }
}
