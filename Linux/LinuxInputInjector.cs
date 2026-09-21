using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using DashboardHost.Core;

namespace DashboardHost.Linux
{
    public sealed class LinuxInputInjector : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct InputEvent
        {
            public long TimeSec;
            public long TimeUsec;
            public ushort Type;
            public ushort Code;
            public int Value;
        }

        private const ushort EV_SYN = 0x00;
        private const ushort EV_KEY = 0x01;
        private const ushort EV_ABS = 0x03;

        private const ushort SYN_REPORT = 0x00;

        private const ushort BTN_TOUCH = 0x14a;
        private const ushort BTN_TOOL_PEN = 0x140;
        private const ushort BTN_STYLUS = 0x14b;
        private const ushort BTN_STYLUS2 = 0x14c;

        private const ushort ABS_X = 0x00;
        private const ushort ABS_Y = 0x01;
        private const ushort ABS_PRESSURE = 0x18;
        private const ushort ABS_TILT_X = 0x1a;
        private const ushort ABS_TILT_Y = 0x1b;

        private int _uinputFd = -1;
        private bool _isVirtualTabletReady = false;

        public LinuxInputInjector()
        {
            InitializeUInput();
        }

        private void InitializeUInput()
        {
            if (!RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
            {
                return;
            }

            try
            {
                if (File.Exists("/dev/uinput"))
                {
                    // Attempt to open /dev/uinput
                    _uinputFd = Open("/dev/uinput", 0x0002 /* O_RDWR */ | 0x0004 /* O_NONBLOCK */);
                    if (_uinputFd >= 0)
                    {
                        SetupDevice();
                        _isVirtualTabletReady = true;
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to open /dev/uinput: {ex.Message}");
            }
        }

        private void SetupDevice()
        {
            // UI_SET_EVBIT ioctls
            Ioctl(_uinputFd, 0x40045564 /* UI_SET_EVBIT */, EV_SYN);
            Ioctl(_uinputFd, 0x40045564, EV_KEY);
            Ioctl(_uinputFd, 0x40045564, EV_ABS);

            // Keys
            Ioctl(_uinputFd, 0x40045565 /* UI_SET_KEYBIT */, BTN_TOUCH);
            Ioctl(_uinputFd, 0x40045565, BTN_TOOL_PEN);
            Ioctl(_uinputFd, 0x40045565, BTN_STYLUS);
            Ioctl(_uinputFd, 0x40045565, BTN_STYLUS2);

            // Absolute axes
            Ioctl(_uinputFd, 0x40045566 /* UI_SET_ABSBIT */, ABS_X);
            Ioctl(_uinputFd, 0x40045566, ABS_Y);
            Ioctl(_uinputFd, 0x40045566, ABS_PRESSURE);
            Ioctl(_uinputFd, 0x40045566, ABS_TILT_X);
            Ioctl(_uinputFd, 0x40045566, ABS_TILT_Y);
        }

        public void ProcessPenEvent(PenEventPayload evt)
        {
            if (!_isVirtualTabletReady || _uinputFd < 0)
            {
                // Telemetry fallback
                return;
            }

            Emit(EV_ABS, ABS_X, evt.XNorm);
            Emit(EV_ABS, ABS_Y, evt.YNorm);
            Emit(EV_ABS, ABS_PRESSURE, evt.Pressure);
            Emit(EV_ABS, ABS_TILT_X, evt.TiltX);
            Emit(EV_ABS, ABS_TILT_Y, evt.TiltY);

            Emit(EV_KEY, BTN_TOOL_PEN, 1);
            Emit(EV_KEY, BTN_TOUCH, evt.Contact ? 1 : 0);
            Emit(EV_KEY, BTN_STYLUS, evt.Barrel ? 1 : 0);
            Emit(EV_KEY, BTN_STYLUS2, evt.Eraser ? 1 : 0);

            Emit(EV_SYN, SYN_REPORT, 0);
        }

        private void Emit(ushort type, ushort code, int val)
        {
            var ev = new InputEvent
            {
                Type = type,
                Code = code,
                Value = val
            };
            int size = Marshal.SizeOf(ev);
            IntPtr ptr = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.StructureToPtr(ev, ptr, false);
                Write(_uinputFd, ptr, (UIntPtr)size);
            }
            finally
            {
                Marshal.FreeHGlobal(ptr);
            }
        }

        [DllImport("libc", EntryPoint = "open", SetLastError = true)]
        private static extern int Open(string pathname, int flags);

        [DllImport("libc", EntryPoint = "ioctl", SetLastError = true)]
        private static extern int Ioctl(int fd, uint request, int val);

        [DllImport("libc", EntryPoint = "write", SetLastError = true)]
        private static extern IntPtr Write(int fd, IntPtr buf, UIntPtr count);

        [DllImport("libc", EntryPoint = "close", SetLastError = true)]
        private static extern int Close(int fd);

        public void Dispose()
        {
            if (_uinputFd >= 0)
            {
                try
                {
                    Ioctl(_uinputFd, 0x5502 /* UI_DEV_DESTROY */, 0);
                    Close(_uinputFd);
                }
                catch { }
                _uinputFd = -1;
            }
        }
    }
}
