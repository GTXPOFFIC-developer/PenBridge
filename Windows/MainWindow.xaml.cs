using System;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using DashboardHost.Core;

namespace DashboardHost
{
    public partial class MainWindow : Window
    {
        private readonly DeviceManager _deviceManager;
        private readonly GoogleAuthService _googleAuth;
        private readonly InputInjector _inputInjector;
        private readonly SettingsStore _settingsStore;
        private System.Windows.Forms.NotifyIcon? _notifyIcon;
        private bool _isExplicitExit = false;
        private readonly NetworkServer _server;
        private readonly UpdateService _updateService;

        public MainWindow()
        {
            InitializeComponent();

            _deviceManager = new DeviceManager();
            _googleAuth = new GoogleAuthService();
            _inputInjector = new InputInjector();
            _settingsStore = new SettingsStore();
            _server = new NetworkServer(_deviceManager, _googleAuth, _inputInjector);
            _updateService = new UpdateService();

            InitializeState();
            WireEvents();

            if (_settingsStore.Settings.AutoStartServer)
                _server.Start();
        }

        private void InitializeState()
        {
            PairingCodeText.Text = FormatPin(_deviceManager.CurrentPairingCode);

            DisplayTargetCombo.Items.Add("Primary Screen");
            DisplayTargetCombo.Items.Add("Virtual Desktop");
            DisplayTargetCombo.SelectedIndex = _settingsStore.Settings.MappingTarget == MappingTarget.VirtualScreen ? 1 : 0;

            StartWithWindowsChk.IsChecked = _settingsStore.Settings.StartWithWindows;

            UpdateGoogleUi(_googleAuth.CurrentAccount);
            UpdateLocalIps();

            SetupTrayIcon();
            RefreshDevicesList();

            _updateService.LogMessage += msg => _server.Log(msg);
            _updateService.UpdateChecked += (hasUpdate, version, url) =>
            {
                if (hasUpdate && !string.IsNullOrEmpty(url))
                {
                    Dispatcher.Invoke(() =>
                    {
                        var res = MessageBox.Show(
                            $"A new version (v{version}) is available!\nWould you like to auto-update now without running an installer?",
                            "PenBridge Update Available",
                            MessageBoxButton.YesNo,
                            MessageBoxImage.Information);
                        if (res == MessageBoxResult.Yes)
                        {
                            _ = _updateService.ApplyUpdateAsync(url);
                        }
                    });
                }
            };
            Task.Run(() => _updateService.CheckForUpdatesAsync());
        }

        private void WireEvents()
        {
            _server.LogMessage += msg =>
            {
                Dispatcher.InvokeAsync(() =>
                {
                    string time = DateTime.Now.ToString("HH:mm:ss");
                    LogTextBox.AppendText($"[{time}] {msg}\n");
                    LogTextBox.ScrollToEnd();
                    FooterStatusText.Text = msg;
                }, System.Windows.Threading.DispatcherPriority.Background);
            };

            _server.ServerStateChanged += running =>
            {
                Dispatcher.Invoke(() =>
                {
                    if (running)
                    {
                        ServerStatusBadge.Background = new SolidColorBrush(Color.FromRgb(0x16, 0x38, 0x2C));
                        ServerStatusBadge.BorderBrush = (SolidColorBrush)FindResource("StatusGreen");
                        StatusLed.Fill = (SolidColorBrush)FindResource("StatusGreen");
                        ServerStatusText.Text = "LISTENING";
                        ServerStatusText.Foreground = (SolidColorBrush)FindResource("StatusGreen");
                        ToggleServerBtn.Content = "Stop";
                    }
                    else
                    {
                        ServerStatusBadge.Background = new SolidColorBrush(Color.FromRgb(0x3B, 0x1E, 0x24));
                        ServerStatusBadge.BorderBrush = (SolidColorBrush)FindResource("StatusRed");
                        StatusLed.Fill = (SolidColorBrush)FindResource("StatusRed");
                        ServerStatusText.Text = "STOPPED";
                        ServerStatusText.Foreground = (SolidColorBrush)FindResource("StatusRed");
                        ToggleServerBtn.Content = "Start";
                    }
                });
            };

            _googleAuth.AccountChanged += account =>
            {
                Dispatcher.Invoke(() => UpdateGoogleUi(account));
            };

            _deviceManager.DevicesChanged += () =>
            {
                Dispatcher.Invoke(RefreshDevicesList);
            };

            long lastUiUpdateMs = 0;
            _inputInjector.PenSampleReceived += (x, y, pressure, contact, tiltX, tiltY) =>
            {
                long now = Environment.TickCount64;
                if (now - lastUiUpdateMs < 50) return; // Cap WPF UI mirror updates to 20 FPS (prevents UI thread lag)
                lastUiUpdateMs = now;

                Dispatcher.InvokeAsync(() =>
                {
                    CoordsText.Text = $"{x} · {y}";
                    TiltText.Text = $"{tiltX}° · {tiltY}°";
                    PressureText.Text = $"{(pressure * 100 / 1024)}%";
                    PressureGauge.Value = pressure;
                    StateText.Text = contact ? "CONTACT" : "HOVER";
                    StateText.Foreground = contact
                        ? (SolidColorBrush)FindResource("StatusGreen")
                        : (SolidColorBrush)FindResource("StatusAmber");
                }, System.Windows.Threading.DispatcherPriority.Background);
            };

            Closing += (s, e) =>
            {
                if (!_isExplicitExit)
                {
                    e.Cancel = true;
                    Hide();
                    _notifyIcon?.ShowBalloonTip(2000, "PenBridge Host", "Running in background in the system tray.", System.Windows.Forms.ToolTipIcon.Info);
                }
                else
                {
                    if (_notifyIcon != null)
                    {
                        _notifyIcon.Visible = false;
                        _notifyIcon.Dispose();
                    }
                    _server.Dispose();
                    _inputInjector.Dispose();
                }
            };

            StateChanged += (s, e) =>
            {
                if (WindowState == WindowState.Minimized)
                {
                    Hide();
                }
            };
        }

        private void SetupTrayIcon()
        {
            try
            {
                _notifyIcon = new System.Windows.Forms.NotifyIcon
                {
                    Icon = System.Drawing.SystemIcons.Application,
                    Text = "PenBridge Host (Listening)",
                    Visible = true
                };

                var contextMenu = new System.Windows.Forms.ContextMenuStrip();
                contextMenu.Items.Add("Open PenBridge Host", null, (s, e) =>
                {
                    Show();
                    WindowState = WindowState.Normal;
                    Activate();
                });
                contextMenu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
                contextMenu.Items.Add("Exit", null, (s, e) =>
                {
                    _isExplicitExit = true;
                    Close();
                });

                _notifyIcon.ContextMenuStrip = contextMenu;
                _notifyIcon.DoubleClick += (s, e) =>
                {
                    Show();
                    WindowState = WindowState.Normal;
                    Activate();
                };
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to setup tray icon: {ex.Message}");
            }
        }

        private void UpdateGoogleUi(GoogleAccountInfo? account)
        {
            if (account != null && !string.IsNullOrEmpty(account.Email))
            {
                GoogleSignedInPanel.Visibility = Visibility.Visible;
                GoogleSignedOutPanel.Visibility = Visibility.Collapsed;
                GoogleUserEmail.Text = account.Email;
                GoogleUserName.Text = string.IsNullOrEmpty(account.DisplayName) ? account.Email : account.DisplayName;
                GoogleUserInitials.Text = (string.IsNullOrEmpty(account.DisplayName) ? account.Email : account.DisplayName)[0].ToString().ToUpper();
            }
            else
            {
                GoogleSignedInPanel.Visibility = Visibility.Collapsed;
                GoogleSignedOutPanel.Visibility = Visibility.Visible;
            }
        }

        private void RefreshDevicesList()
        {
            var devices = _deviceManager.GetAllowedDevices().ToList();
            DevicesList.ItemsSource = null;
            DevicesList.ItemsSource = devices;
            NoDevicesNotice.Visibility = devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private void UpdateLocalIps()
        {
            try
            {
                var ips = Dns.GetHostAddresses(Dns.GetHostName())
                    .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    .Select(ip => ip.ToString()).ToList();

                LocalIpText.Text = ips.Count > 0
                    ? $"Wi-Fi: {string.Join(", ", ips)}  ·  UDP 41173/41174"
                    : "Wi-Fi: 127.0.0.1  ·  UDP 41173/41174";
            }
            catch
            {
                LocalIpText.Text = "Wi-Fi: detecting…";
            }
        }

        private static string FormatPin(string pin)
        {
            pin = pin.PadLeft(6, '0');
            return pin.Length == 6 ? $"{pin[..3]} {pin[3..]}" : pin;
        }

        // ── Event Handlers ──────────────────────────────────────────────────

        private void ToggleServerBtn_Click(object sender, RoutedEventArgs e)
        {
            if (_server.IsRunning) _server.Stop();
            else _server.Start();
        }

        private async void GoogleSignInBtn_Click(object sender, RoutedEventArgs e)
        {
            GoogleSignInBtn.IsEnabled = false;
            GoogleSignInBtn.Content = "Opening browser…";
            try
            {
                var account = await _googleAuth.SignInAsync();
                if (account == null)
                    _server.Log("Google sign-in cancelled or timed out.");
            }
            finally
            {
                GoogleSignInBtn.IsEnabled = true;
                GoogleSignInBtn.Content = "Sign in with Google";
            }
        }

        private void GoogleSignOutBtn_Click(object sender, RoutedEventArgs e)
        {
            _googleAuth.SignOut();
        }

        private void RegenPinBtn_Click(object sender, RoutedEventArgs e)
        {
            _deviceManager.GenerateNewPairingCode();
            PairingCodeText.Text = FormatPin(_deviceManager.CurrentPairingCode);
            _server.Log($"New pairing PIN generated: {_deviceManager.CurrentPairingCode}");
        }

        private void RevokeDeviceBtn_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is string deviceId)
                _deviceManager.RevokeDevice(deviceId);
        }

        private async void AdbReverseBtn_Click(object sender, RoutedEventArgs e)
        {
            AdbReverseBtn.IsEnabled = false;
            AdbReverseBtn.Content = "Checking ADB…";
            AdbStatusBorder.Visibility = Visibility.Collapsed;

            try
            {
                var result = await AdbHelper.SetupReverseAsync();

                // Show inline status — no more MessageBox popup
                AdbStatusBorder.Visibility = Visibility.Visible;
                AdbStatusTitle.Text = result.Message;
                AdbStatusGuidance.Text = result.Guidance ?? "";

                if (result.Success)
                {
                    AdbStatusBorder.BorderBrush = (SolidColorBrush)FindResource("StatusGreen");
                    AdbStatusTitle.Foreground = (SolidColorBrush)FindResource("StatusGreen");
                    AdbStatusGuidance.Foreground = (SolidColorBrush)FindResource("TextMuted");
                }
                else if (result.Status == AdbStatus.DeviceUnauthorized)
                {
                    AdbStatusBorder.BorderBrush = (SolidColorBrush)FindResource("StatusAmber");
                    AdbStatusTitle.Foreground = (SolidColorBrush)FindResource("StatusAmber");
                    AdbStatusGuidance.Foreground = (SolidColorBrush)FindResource("TextPrimary");
                }
                else
                {
                    AdbStatusBorder.BorderBrush = (SolidColorBrush)FindResource("StatusRed");
                    AdbStatusTitle.Foreground = (SolidColorBrush)FindResource("StatusRed");
                    AdbStatusGuidance.Foreground = (SolidColorBrush)FindResource("TextMuted");
                }

                _server.Log($"ADB: {result.Message}");
            }
            finally
            {
                AdbReverseBtn.IsEnabled = true;
                AdbReverseBtn.Content = "⚡  Setup USB Tunnel (adb reverse)";
            }
        }

        private void ClearLogBtn_Click(object sender, RoutedEventArgs e)
        {
            LogTextBox.Clear();
        }

        private void DisplayTargetCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_inputInjector == null || _settingsStore == null) return;
            var target = DisplayTargetCombo.SelectedIndex == 1 ? MappingTarget.VirtualScreen : MappingTarget.PrimaryScreen;
            _inputInjector.Target = target;
            _settingsStore.Settings.MappingTarget = target;
            _settingsStore.Save();
        }

        private void StartWithWindowsChk_Changed(object sender, RoutedEventArgs e)
        {
            if (_settingsStore == null) return;
            _settingsStore.Settings.StartWithWindows = StartWithWindowsChk.IsChecked == true;
            _settingsStore.Save();
        }

        private void MinimizeBtn_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState.Minimized;
        }

        private void MaximizeBtn_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
        }

        private void CloseBtn_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private async void CheckUpdateBtn_Click(object sender, RoutedEventArgs e)
        {
            CheckUpdateBtn.IsEnabled = false;
            CheckUpdateBtn.Content = "Checking...";
            try
            {
                var (hasUpdate, version, url) = await _updateService.CheckForUpdatesAsync();
                if (hasUpdate && !string.IsNullOrEmpty(url))
                {
                    var res = MessageBox.Show(
                        $"New version v{version} is available!\nUpdate now seamlessly without running an installer?",
                        "Update Available",
                        MessageBoxButton.YesNo,
                        MessageBoxImage.Information);
                    if (res == MessageBoxResult.Yes)
                    {
                        await _updateService.ApplyUpdateAsync(url);
                    }
                }
                else
                {
                    MessageBox.Show(
                        $"You are running the latest version (v{UpdateService.CurrentVersion}).",
                        "PenBridge Up to Date",
                        MessageBoxButton.OK,
                        MessageBoxImage.Information);
                }
            }
            finally
            {
                CheckUpdateBtn.IsEnabled = true;
                CheckUpdateBtn.Content = "⟳ Update";
            }
        }
    }
}
