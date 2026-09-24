using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Forms;

namespace DashboardHost.Core
{
    public enum MappingTarget
    {
        PrimaryScreen,
        VirtualScreen,
        SpecificMonitor,
        CustomRegion
    }

    public enum BarrelActionType
    {
        RightClick,
        MiddleClick,
        Eraser,
        DoubleClick,
        Undo
    }

    public sealed class InputInjector : IDisposable
    {
        #region Win32 Pointer Injection Structs & Enums

        public enum POINTER_INPUT_TYPE
        {
            PT_POINTER = 1,
            PT_TOUCH = 2,
            PT_PEN = 3,
            PT_TOUCHPAD = 4
        }

        public enum POINTER_FEEDBACK_MODE
        {
            POINTER_FEEDBACK_DEFAULT = 1,
            POINTER_FEEDBACK_INDIRECT = 2,
            POINTER_FEEDBACK_NONE = 3
        }

        [Flags]
        public enum POINTER_FLAGS
        {
            POINTER_FLAG_NONE = 0x00000000,
            POINTER_FLAG_NEW = 0x00000001,
            POINTER_FLAG_INRANGE = 0x00000002,
            POINTER_FLAG_INCONTACT = 0x00000004,
            POINTER_FLAG_FIRSTBUTTON = 0x00000010,
            POINTER_FLAG_SECONDBUTTON = 0x00000020,
            POINTER_FLAG_THIRDBUTTON = 0x00000040,
            POINTER_FLAG_FOURTHBUTTON = 0x00000080,
            POINTER_FLAG_FIFTHBUTTON = 0x00000100,
            POINTER_FLAG_PRIMARY = 0x00002000,
            POINTER_FLAG_CONFIDENCE = 0x00004000,
            POINTER_FLAG_CANCELED = 0x00008000,
            POINTER_FLAG_DOWN = 0x00010000,
            POINTER_FLAG_UPDATE = 0x00020000,
            POINTER_FLAG_UP = 0x00040000,
            POINTER_FLAG_WHEEL = 0x00080000,
            POINTER_FLAG_HWHEEL = 0x00100000,
            POINTER_FLAG_CAPTURECHANGED = 0x00200000,
            POINTER_FLAG_HASTRANSFORM = 0x00400000
        }

        [Flags]
        public enum PEN_FLAGS
        {
            PEN_FLAG_NONE = 0x00000000,
            PEN_FLAG_BARREL = 0x00000001,
            PEN_FLAG_INVERTED = 0x00000002,
            PEN_FLAG_ERASER = 0x00000004
        }

        [Flags]
        public enum PEN_MASK
        {
            PEN_MASK_NONE = 0x00000000,
            PEN_MASK_PRESSURE = 0x00000001,
            PEN_MASK_ROTATION = 0x00000002,
            PEN_MASK_TILT_X = 0x00000004,
            PEN_MASK_TILT_Y = 0x00000008
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct POINT
        {
            public int x;
            public int y;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct POINTER_INFO
        {
            public POINTER_INPUT_TYPE pointerType;
            public uint pointerId;
            public uint frameId;
            public POINTER_FLAGS pointerFlags;
            public IntPtr sourceDevice;
            public IntPtr hwndTarget;
            public POINT ptPixelLocation;
            public POINT ptHimetricLocation;
            public POINT ptPixelLocationRaw;
            public POINT ptHimetricLocationRaw;
            public uint dwTime;
            public uint historyCount;
            public int InputData;
            public uint KeyStates;
            public ulong PerformanceCount;
            public int ButtonChangeType;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct POINTER_PEN_INFO
        {
            public POINTER_INFO pointerInfo;
            public PEN_FLAGS penFlags;
            public PEN_MASK penMask;
            public uint pressure;
            public uint rotation;
            public int tiltX;
            public int tiltY;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct POINTER_TYPE_INFO
        {
            public POINTER_INPUT_TYPE type;
            public POINTER_PEN_INFO penInfo;
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr CreateSyntheticPointerDevice(POINTER_INPUT_TYPE pointerType, uint maxCount, POINTER_FEEDBACK_MODE mode);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool InjectSyntheticPointerInput(IntPtr device, [In] POINTER_TYPE_INFO[] pointerInfo, uint count);

        [DllImport("user32.dll")]
        private static extern void DestroySyntheticPointerDevice(IntPtr device);

        [DllImport("user32.dll")]
        private static extern bool SetCursorPos(int X, int Y);

        [DllImport("user32.dll")]
        private static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        private static extern int GetSystemMetrics(int nIndex);

        private const int SM_XVIRTUALSCREEN = 76;
        private const int SM_YVIRTUALSCREEN = 77;
        private const int SM_CXVIRTUALSCREEN = 78;
        private const int SM_CYVIRTUALSCREEN = 79;
        private const int SM_CXSCREEN = 0;
        private const int SM_CYSCREEN = 1;

        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
        private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const byte VK_CONTROL = 0x11;
        private const byte VK_Z = 0x5A;

        #endregion

        private IntPtr _syntheticPenDevice = IntPtr.Zero;
        private bool _useSyntheticPen = true;
        private bool _syntheticPenInContact = false;
        private bool _mouseInContact = false;
        private bool _disposed = false;

        public MappingTarget Target { get; set; } = MappingTarget.PrimaryScreen;
        public int SelectedMonitorIndex { get; set; } = 0;
        public bool PreserveAspectRatio { get; set; } = false;
        public Rect CustomRegion { get; set; } = Rect.Empty;

        public BarrelActionType ConfiguredBarrelAction { get; set; } = BarrelActionType.RightClick;
        public bool RawInputMode { get; set; } = false;

        public event Action<int, int, int, bool, int, int>? PenSampleReceived;

        public InputInjector()
        {
            InitializeDevice();
        }

        private void InitializeDevice()
        {
            try
            {
                _syntheticPenDevice = CreateSyntheticPointerDevice(
                    POINTER_INPUT_TYPE.PT_PEN,
                    1,
                    POINTER_FEEDBACK_MODE.POINTER_FEEDBACK_DEFAULT);

                if (_syntheticPenDevice == IntPtr.Zero)
                {
                    Debug.WriteLine("Synthetic pen device creation failed. Falling back to mouse input.");
                    _useSyntheticPen = false;
                }
                else
                {
                    // Probe if synthetic pen injection is permitted without uiAccess elevation
                    var probe = new POINTER_TYPE_INFO[1];
                    probe[0].type = POINTER_INPUT_TYPE.PT_PEN;
                    probe[0].penInfo.pointerInfo.pointerType = POINTER_INPUT_TYPE.PT_PEN;
                    probe[0].penInfo.pointerInfo.pointerId = 1;
                    probe[0].penInfo.pointerInfo.pointerFlags = POINTER_FLAGS.POINTER_FLAG_UPDATE | POINTER_FLAGS.POINTER_FLAG_INRANGE;
                    bool canInject = InjectSyntheticPointerInput(_syntheticPenDevice, probe, 1);
                    if (!canInject)
                    {
                        Debug.WriteLine("Synthetic pen lacks UI access. Using Universal Direct Mouse Injection for 100% drawing compatibility.");
                        _useSyntheticPen = false;
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Failed to init synthetic pointer: {ex.Message}");
                _useSyntheticPen = false;
            }
        }

        private readonly POINTER_TYPE_INFO[] _pointerArray = new POINTER_TYPE_INFO[1];

        public void ProcessPenEvent(PenEventPayload evt)
        {
            if (_disposed) return;

            if (evt.Relative)
            {
                ProcessRelativeEvent(evt);
                return;
            }

            // Map normalized coordinates (0..65535) to screen pixels
            (int screenX, int screenY) = MapCoordinates(evt.XNorm, evt.YNorm);

            // Scale pressure: device sends 0..65535 -> Windows pen takes 0..1024
            uint pressure1024 = (uint)Math.Clamp((evt.Pressure * 1024) / 65535, 0, 1024);

            // Scale tilt: device sends centidegrees (-900..900) -> Windows takes degrees (-90..90)
            int tiltXDeg = Math.Clamp(evt.TiltX / 10, -90, 90);
            int tiltYDeg = Math.Clamp(evt.TiltY / 10, -90, 90);

            PenSampleReceived?.Invoke(screenX, screenY, (int)pressure1024, evt.Contact, tiltXDeg, tiltYDeg);

            // In Raw Input / OSU! Mode, or if synthetic pen is unavailable: inject high-precision absolute mouse events
            if (RawInputMode || !_useSyntheticPen || _syntheticPenDevice == IntPtr.Zero)
            {
                InjectMouse(evt, screenX, screenY);
            }
            else
            {
                InjectPen(evt, screenX, screenY, pressure1024, tiltXDeg, tiltYDeg);
            }
        }

        private void ProcessRelativeEvent(PenEventPayload evt)
        {
            if (evt.Action == ProtocolConst.ActionScroll)
            {
                short scrollDelta = (short)evt.YNorm;
                int wheelAmount = scrollDelta * 15;
                mouse_event(MOUSEEVENTF_WHEEL, 0, 0, unchecked((uint)wheelAmount), UIntPtr.Zero);
                return;
            }

            short dx = (short)evt.XNorm;
            short dy = (short)evt.YNorm;

            if (dx != 0 || dy != 0)
            {
                mouse_event(MOUSEEVENTF_MOVE, unchecked((uint)dx), unchecked((uint)dy), 0, UIntPtr.Zero);
            }

            if (evt.Barrel || evt.Middle || evt.DoubleClick || evt.Undo)
            {
                ExecuteBarrelAction(evt, 0, 0, isRelative: true);
                return;
            }

            if (evt.Action == ProtocolConst.ActionDown)
            {
                mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                _mouseInContact = true;
            }
            else if (evt.Action == ProtocolConst.ActionUp)
            {
                mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                _mouseInContact = false;
            }
        }

        private void InjectPen(PenEventPayload evt, int x, int y, uint pressure, int tiltX, int tiltY)
        {
            if (!_useSyntheticPen || _syntheticPenDevice == IntPtr.Zero)
            {
                InjectMouse(evt, x, y);
                return;
            }

            // Always update Windows system cursor position so all desktop apps (OpenBoard, Studio, Krita, Paint) receive continuous strokes
            SetCursorPos(x, y);

            var pointerInfo = new POINTER_TYPE_INFO
            {
                type = POINTER_INPUT_TYPE.PT_PEN
            };

            ref var pen = ref pointerInfo.penInfo;
            ref var pi = ref pen.pointerInfo;

            pi.pointerType = POINTER_INPUT_TYPE.PT_PEN;
            pi.pointerId = 1;
            pi.ptPixelLocation.x = x;
            pi.ptPixelLocation.y = y;

            POINTER_FLAGS flags = POINTER_FLAGS.POINTER_FLAG_PRIMARY;
            PEN_FLAGS penFlags = PEN_FLAGS.PEN_FLAG_NONE;

            if (evt.Barrel) penFlags |= PEN_FLAGS.PEN_FLAG_BARREL;
            if (evt.Eraser) penFlags |= PEN_FLAGS.PEN_FLAG_ERASER | PEN_FLAGS.PEN_FLAG_INVERTED;

            pen.penFlags = penFlags;
            pen.penMask = PEN_MASK.PEN_MASK_PRESSURE | PEN_MASK.PEN_MASK_TILT_X | PEN_MASK.PEN_MASK_TILT_Y;
            pen.pressure = pressure;
            pen.tiltX = tiltX;
            pen.tiltY = tiltY;

            switch (evt.Action)
            {
                case ProtocolConst.ActionDown:
                    flags |= POINTER_FLAGS.POINTER_FLAG_INRANGE | POINTER_FLAGS.POINTER_FLAG_INCONTACT |
                             POINTER_FLAGS.POINTER_FLAG_DOWN | POINTER_FLAGS.POINTER_FLAG_FIRSTBUTTON;
                    _syntheticPenInContact = true;
                    break;

                case ProtocolConst.ActionMove:
                    flags |= POINTER_FLAGS.POINTER_FLAG_INRANGE | POINTER_FLAGS.POINTER_FLAG_UPDATE;
                    if (evt.Contact)
                    {
                        flags |= POINTER_FLAGS.POINTER_FLAG_INCONTACT | POINTER_FLAGS.POINTER_FLAG_FIRSTBUTTON;
                        _syntheticPenInContact = true;
                    }
                    else if (_syntheticPenInContact)
                    {
                        flags |= POINTER_FLAGS.POINTER_FLAG_UP;
                        _syntheticPenInContact = false;
                    }
                    break;

                case ProtocolConst.ActionHover:
                    flags |= POINTER_FLAGS.POINTER_FLAG_INRANGE | POINTER_FLAGS.POINTER_FLAG_UPDATE;
                    if (_syntheticPenInContact)
                    {
                        flags |= POINTER_FLAGS.POINTER_FLAG_UP;
                        _syntheticPenInContact = false;
                    }
                    break;

                case ProtocolConst.ActionUp:
                default:
                    if (_syntheticPenInContact)
                    {
                        flags |= POINTER_FLAGS.POINTER_FLAG_UP | POINTER_FLAGS.POINTER_FLAG_INRANGE;
                        _syntheticPenInContact = false;
                    }
                    else
                    {
                        flags |= POINTER_FLAGS.POINTER_FLAG_UPDATE;
                    }
                    break;
            }

            pi.pointerFlags = flags;

            _pointerArray[0] = pointerInfo;
            bool success = InjectSyntheticPointerInput(_syntheticPenDevice, _pointerArray, 1);
            if (!success)
            {
                // Fall back to mouse injection permanently for this session if synthetic pen input lacks privileges
                _useSyntheticPen = false;
                Debug.WriteLine("InjectSyntheticPointerInput failed. Falling back to high-precision mouse injection.");
                InjectMouse(evt, x, y);
            }
        }

        private void InjectMouse(PenEventPayload evt, int x, int y)
        {
            // Position cursor on screen for all applications (OpenBoard, Studio, Krita, Photoshop, Paint, etc.)
            SetCursorPos(x, y);

            if (evt.Barrel || evt.Middle || evt.DoubleClick || evt.Undo)
            {
                ExecuteBarrelAction(evt, x, y, isRelative: false);
                return;
            }

            if (evt.Action == ProtocolConst.ActionDown || (evt.Contact && !_mouseInContact))
            {
                mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                _mouseInContact = true;
            }
            else if (evt.Action == ProtocolConst.ActionMove && evt.Contact)
            {
                if (!_mouseInContact)
                {
                    mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                    _mouseInContact = true;
                }
                else
                {
                    // Drag movement while contact is maintained: generates smooth, continuous stroke
                    mouse_event(MOUSEEVENTF_MOVE, 0, 0, 0, UIntPtr.Zero);
                }
            }
            else if (evt.Action == ProtocolConst.ActionUp || (!evt.Contact && _mouseInContact))
            {
                mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                _mouseInContact = false;
            }
            else if (evt.Action == ProtocolConst.ActionHover && _mouseInContact)
            {
                mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                _mouseInContact = false;
            }
        }

        private void ExecuteBarrelAction(PenEventPayload evt, int x, int y, bool isRelative)
        {
            var action = ConfiguredBarrelAction;
            if (evt.Undo) action = BarrelActionType.Undo;
            else if (evt.DoubleClick) action = BarrelActionType.DoubleClick;
            else if (evt.Middle) action = BarrelActionType.MiddleClick;
            else if (evt.Eraser) action = BarrelActionType.Eraser;

            switch (action)
            {
                case BarrelActionType.Undo:
                    keybd_event(VK_CONTROL, 0, 0, UIntPtr.Zero);
                    keybd_event(VK_Z, 0, 0, UIntPtr.Zero);
                    keybd_event(VK_Z, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
                    keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
                    break;

                case BarrelActionType.DoubleClick:
                    if (isRelative)
                    {
                        mouse_event(MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                        mouse_event(MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                    }
                    else
                    {
                        SendAbsoluteMouse(MOUSEEVENTF_LEFTDOWN, x, y);
                        SendAbsoluteMouse(MOUSEEVENTF_LEFTUP, x, y);
                        SendAbsoluteMouse(MOUSEEVENTF_LEFTDOWN, x, y);
                        SendAbsoluteMouse(MOUSEEVENTF_LEFTUP, x, y);
                    }
                    break;

                case BarrelActionType.MiddleClick:
                    if (isRelative)
                    {
                        mouse_event(MOUSEEVENTF_MIDDLEDOWN | MOUSEEVENTF_MIDDLEUP, 0, 0, 0, UIntPtr.Zero);
                    }
                    else
                    {
                        SendAbsoluteMouse(MOUSEEVENTF_MIDDLEDOWN, x, y);
                        SendAbsoluteMouse(MOUSEEVENTF_MIDDLEUP, x, y);
                    }
                    break;

                case BarrelActionType.Eraser:
                case BarrelActionType.RightClick:
                default:
                    if (isRelative)
                    {
                        mouse_event(MOUSEEVENTF_RIGHTDOWN | MOUSEEVENTF_RIGHTUP, 0, 0, 0, UIntPtr.Zero);
                    }
                    else
                    {
                        SendAbsoluteMouse(MOUSEEVENTF_RIGHTDOWN, x, y);
                        SendAbsoluteMouse(MOUSEEVENTF_RIGHTUP, x, y);
                    }
                    break;
            }
        }

        private void SendAbsoluteMouse(uint flags, int screenX, int screenY)
        {
            int vLeft = GetSystemMetrics(SM_XVIRTUALSCREEN);
            int vTop = GetSystemMetrics(SM_YVIRTUALSCREEN);
            int vWidth = GetSystemMetrics(SM_CXVIRTUALSCREEN);
            int vHeight = GetSystemMetrics(SM_CYVIRTUALSCREEN);

            if (vWidth <= 0) vWidth = GetSystemMetrics(SM_CXSCREEN);
            if (vHeight <= 0) vHeight = GetSystemMetrics(SM_CYSCREEN);
            if (vWidth <= 0) vWidth = 1920;
            if (vHeight <= 0) vHeight = 1080;

            uint normX = (uint)Math.Clamp((int)(((screenX - vLeft) * 65535.0 / vWidth) + 0.5), 0, 65535);
            uint normY = (uint)Math.Clamp((int)(((screenY - vTop) * 65535.0 / vHeight) + 0.5), 0, 65535);

            mouse_event(flags | MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, normX, normY, 0, UIntPtr.Zero);
        }

        private (int x, int y) MapCoordinates(ushort xNorm, ushort yNorm)
        {
            int originX = 0, originY = 0, width = 1920, height = 1080;

            switch (Target)
            {
                case MappingTarget.VirtualScreen:
                    originX = GetSystemMetrics(SM_XVIRTUALSCREEN);
                    originY = GetSystemMetrics(SM_YVIRTUALSCREEN);
                    width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
                    height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
                    break;

                case MappingTarget.SpecificMonitor:
                    var screens = Screen.AllScreens;
                    if (screens != null && SelectedMonitorIndex >= 0 && SelectedMonitorIndex < screens.Length)
                    {
                        var sc = screens[SelectedMonitorIndex];
                        originX = sc.Bounds.X;
                        originY = sc.Bounds.Y;
                        width = sc.Bounds.Width;
                        height = sc.Bounds.Height;
                    }
                    else
                    {
                        goto case MappingTarget.PrimaryScreen;
                    }
                    break;

                case MappingTarget.CustomRegion:
                    if (!CustomRegion.IsEmpty && CustomRegion.Width > 10 && CustomRegion.Height > 10)
                    {
                        originX = (int)CustomRegion.X;
                        originY = (int)CustomRegion.Y;
                        width = (int)CustomRegion.Width;
                        height = (int)CustomRegion.Height;
                        break;
                    }
                    goto default;

                case MappingTarget.PrimaryScreen:
                default:
                    originX = 0;
                    originY = 0;
                    width = GetSystemMetrics(SM_CXSCREEN);
                    height = GetSystemMetrics(SM_CYSCREEN);
                    break;
            }

            if (width <= 0) width = 1920;
            if (height <= 0) height = 1080;

            if (PreserveAspectRatio)
            {
                // Tablet surface is assumed 16:10 or 16:9; adjust target rect proportionally
                double targetAspect = (double)width / height;
                double tabletAspect = 16.0 / 10.0;

                if (targetAspect > tabletAspect)
                {
                    // Pillarbox: target is wider than tablet
                    int adjustedWidth = (int)(height * tabletAspect);
                    originX += (width - adjustedWidth) / 2;
                    width = adjustedWidth;
                }
                else if (targetAspect < tabletAspect)
                {
                    // Letterbox: target is taller than tablet
                    int adjustedHeight = (int)(width / tabletAspect);
                    originY += (height - adjustedHeight) / 2;
                    height = adjustedHeight;
                }
            }

            int mappedX = originX + (int)((xNorm / 65535.0) * width);
            int mappedY = originY + (int)((yNorm / 65535.0) * height);

            return (mappedX, mappedY);
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;

            if (_syntheticPenDevice != IntPtr.Zero)
            {
                try
                {
                    DestroySyntheticPointerDevice(_syntheticPenDevice);
                }
                catch { }
                _syntheticPenDevice = IntPtr.Zero;
            }
        }
    }
}
