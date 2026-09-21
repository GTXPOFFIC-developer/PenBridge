using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;

namespace DashboardHost
{
    public partial class App : Application
    {
        private static EventWaitHandle? _restoreHandle;
        private static Mutex? _instanceMutex;

        protected override void OnStartup(StartupEventArgs e)
        {
            const string mutexName = "DashboardHost_SingleInstance_Mutex";
            const string eventName = "DashboardHost_Restore_Signal";

            _instanceMutex = new Mutex(true, mutexName, out bool isNewInstance);
            _restoreHandle = new EventWaitHandle(false, EventResetMode.AutoReset, eventName, out _);

            if (!isNewInstance)
            {
                // An instance is already running in the background/tray! Signal it to show window and exit immediately.
                _restoreHandle.Set();
                Environment.Exit(0);
                return;
            }

            base.OnStartup(e);

            var mainWindow = new MainWindow();
            MainWindow = mainWindow;
            mainWindow.Show();

            // Listen for subsequent launch attempts to bring the window back to foreground
            Task.Run(() =>
            {
                while (_restoreHandle.WaitOne())
                {
                    Dispatcher.InvokeAsync(() =>
                    {
                        if (MainWindow is MainWindow mw)
                        {
                            mw.Show();
                            if (mw.WindowState == WindowState.Minimized)
                                mw.WindowState = WindowState.Normal;
                            mw.Activate();
                            mw.Topmost = true;
                            mw.Topmost = false;
                            mw.Focus();
                        }
                    });
                }
            });
        }

        protected override void OnExit(ExitEventArgs e)
        {
            try { _restoreHandle?.Dispose(); } catch { }
            try { _instanceMutex?.Dispose(); } catch { }
            base.OnExit(e);
        }
    }
}
