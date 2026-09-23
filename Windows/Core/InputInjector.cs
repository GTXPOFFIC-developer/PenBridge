using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;

namespace DashboardHost.Core
{
    public enum MappingTarget
    {
        PrimaryScreen,
        VirtualScreen,
        CustomRegion
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
        private const uint MOUSEEVENTF_WHEEL = 0x0800;

        #endregion

        private IntPtr _syntheticPenDevice = IntPtr.Zero;
        private bool _useSyntheticPen = true;
        private bool _syntheticPenInContact = false;
        private bool _mouseInContact = false;
        private bool _disposed = false;

        public MappingTarget Target { get; set; } = MappingTarget.PrimaryScreen;
        public Rect CustomRegion { get; set; } = Rect.Empty;

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

            if (_useSyntheticPen && _syntheticPenDevice != IntPtr.Zero)
            {
                InjectPen(evt, screenX, screenY, pressure1024, tiltXDeg, tiltYDeg);
            }
            else
            {
                InjectMouse(evt, screenX, screenY);
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

            if (evt.Barrel)
            {
                // Right click
                mouse_event(MOUSEEVENTF_RIGHTDOWN | MOUSEEVENTF_RIGHTUP, 0, 0, 0, UIntPtr.Zero);
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
            // Update the Windows system cursor position so the pointer is visible system-wide
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
                Debug.WriteLine("InjectSyntheticPointerInput failed. Falling back to mouse event injection.");
                InjectMouse(evt, x, y);
            }
        }

        private void InjectMouse(PenEventPayload evt, int x, int y)
        {
            SetCursorPos(x, y);

            if (evt.Barrel)
            {
                mouse_event(MOUSEEVENTF_RIGHTDOWN | MOUSEEVENTF_RIGHTUP, 0, 0, 0, UIntPtr.Zero);
                return;
            }

            if (evt.Action == ProtocolConst.ActionDown || (evt.Contact && !_mouseInContact))
            {
                mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                _mouseInContact = true;
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
