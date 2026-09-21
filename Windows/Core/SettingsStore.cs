using System;
using System.IO;
using System.Text.Json;
using Microsoft.Win32;

namespace DashboardHost.Core
{
    public sealed class HostSettings
    {
        public bool StartWithWindows { get; set; } = false;
        public bool MinimizeToTray { get; set; } = true;
        public bool AutoStartServer { get; set; } = true;
        public MappingTarget MappingTarget { get; set; } = MappingTarget.PrimaryScreen;
        public int AccentIndex { get; set; } = 0;
    }

    public sealed class SettingsStore
    {
        private readonly string _settingsPath;
        public HostSettings Settings { get; private set; }

        public SettingsStore()
        {
            string appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DashboardHost");
            Directory.CreateDirectory(appData);
            _settingsPath = Path.Combine(appData, "settings.json");
            Settings = LoadSettings();
        }

        public void Save()
        {
            try
            {
                string json = JsonSerializer.Serialize(Settings, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(_settingsPath, json);
                ApplyStartupRegistry(Settings.StartWithWindows);
            }
            catch { }
        }

        private HostSettings LoadSettings()
        {
            try
            {
                if (File.Exists(_settingsPath))
                {
                    string json = File.ReadAllText(_settingsPath);
                    return JsonSerializer.Deserialize<HostSettings>(json) ?? new HostSettings();
                }
            }
            catch { }
            return new HostSettings();
        }

        private static void ApplyStartupRegistry(bool startWithWindows)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
                if (key == null) return;

                string appPath = Environment.ProcessPath ?? "";
                if (startWithWindows && !string.IsNullOrEmpty(appPath))
                {
                    key.SetValue("DashboardHost", $"\"{appPath}\" --minimized");
                }
                else
                {
                    key.DeleteValue("DashboardHost", false);
                }
            }
            catch { }
        }
    }
}
