// Derived from pgratz1/AR-Touchpad IMouseService.aidl (Apache-2.0), trimmed
// to the uinput mouse surface AR-glass-plus needs. See THIRD_PARTY_NOTICES.md.
package com.example.ar_glass_plus.input.uinput;

interface IRootMouseService {
    // Target display + pixel size (contentDisplayId of the hidden VD).
    // Re-associates the uinput device to that display.
    void setDisplay(int displayId, int width, int height) = 1;

    // Relative movement in content pixels; sub-pixel remainders accumulate.
    void moveMouse(float dx, float dy) = 2;

    // Click at current cursor position (button: 1=left, 2=right).
    void click(float x, float y, int button) = 3;

    // Scroll: finger-pixel deltas converted to REL_WHEEL/REL_HWHEEL detents.
    void scroll(float dx, float dy) = 4;

    // Button down/up at the current cursor point (button: 1=left, 2=right, 4=middle).
    void buttonDown(int button) = 5;
    void buttonUp(int button) = 6;

    // Injects a key (e.g. KEYCODE_BACK) to the focused window on the target
    // display via InputManagerGlobal.injectInputEvent (displayId-stamped).
    void pressKey(int androidKeycode) = 7;

    // Absolute move in content coordinates; used by spatial ray hits.
    void moveTo(float x, float y) = 8;

    // Release every possibly-held button (LEFT/RIGHT/MIDDLE up). Safe teardown
    // for unplug / service reconnect / activity recreate.
    void resetInputState() = 9;

    // Destroy the uinput device and close.
    void destroy() = 16777114;
}
