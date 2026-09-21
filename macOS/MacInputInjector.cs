using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using DashboardHost.Core;

namespace DashboardHost.macOS
{
    public sealed class MacInputInjector : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct CGPoint
        {
            public double X;
            public double Y;
        }

        private enum CGEventType : uint
        {
            LeftMouseDown = 1,
            LeftMouseUp = 2,
            RightMouseDown = 3,
            RightMouseUp = 4,
            MouseMoved = 5,
            LeftMouseDragged = 6,
            RightMouseDragged = 7
        }

        private enum CGMouseButton : uint
        {
            Left = 0,
            Right = 1
        }

        private enum CGEventField : uint
        {
            MouseEventPressure = 9,
            TabletEventTiltX = 64,
            TabletEventTiltY = 65,
            TabletEventDeviceID = 66
        }

        private enum CGEventTapLocation : uint
        {
            HIDEventTap = 0,
            SessionEventTap = 1
        }

        private bool _wasInContact = false;

        public void ProcessPenEvent(PenEventPayload evt)
        {
            if (!RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
            {
                return;
            }

            try
            {
                // Map coordinates (assuming 1920x1080 default or main display bounds)
                double x = (evt.XNorm / 65535.0) * 1920.0;
                double y = (evt.YNorm / 65535.0) * 1080.0;
                var pt = new CGPoint { X = x, Y = y };

                CGEventType type;
                if (evt.Contact)
                {
                    type = _wasInContact ? CGEventType.LeftMouseDragged : CGEventType.LeftMouseDown;
                    _wasInContact = true;
                }
                else
                {
                    type = _wasInContact ? CGEventType.LeftMouseUp : CGEventType.MouseMoved;
                    _wasInContact = false;
                }

                IntPtr cgEvent = CGEventCreateMouseEvent(IntPtr.Zero, type, pt, CGMouseButton.Left);
                if (cgEvent != IntPtr.Zero)
                {
                    // Scale pressure: 0..65535 -> 0..255
                    long pressure255 = (evt.Pressure * 255) / 65535;
                    CGEventSetIntegerValueField(cgEvent, CGEventField.MouseEventPressure, pressure255);
                    CGEventSetIntegerValueField(cgEvent, CGEventField.TabletEventTiltX, evt.TiltX);
                    CGEventSetIntegerValueField(cgEvent, CGEventField.TabletEventTiltY, evt.TiltY);

                    CGEventPost(CGEventTapLocation.HIDEventTap, cgEvent);
                    CFRelease(cgEvent);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"MacInputInjector error: {ex.Message}");
            }
        }

        [DllImport("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")]
        private static extern IntPtr CGEventCreateMouseEvent(IntPtr source, CGEventType mouseType, CGPoint mouseCursorPosition, CGMouseButton mouseButton);

        [DllImport("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")]
        private static extern void CGEventSetIntegerValueField(IntPtr @event, CGEventField field, long value);

        [DllImport("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")]
        private static extern void CGEventPost(CGEventTapLocation tap, IntPtr @event);

        [DllImport("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")]
        private static extern void CFRelease(IntPtr cf);

        public void Dispose()
        {
        }
    }
}
