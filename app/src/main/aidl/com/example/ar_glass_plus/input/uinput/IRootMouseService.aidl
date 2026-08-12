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

    // BTN_LEFT down/up for real drag (down -> move -> up).
    void mouseDown() = 5;
    void mouseUp() = 6;

    // Injects a key (e.g. KEYCODE_BACK) to the focused window on the target
    // display via InputManagerGlobal.injectInputEvent (displayId-stamped).
    void pressKey(int androidKeycode) = 7;

    // Touch-drag scroll simulation (verified path on OPPO: injected wheel
    // events do not drive Compose/RecyclerView lists). action: 0=DOWN, 1=MOVE, 2=UP.
    void scrollDrag(float dy, int action) = 8;

    // Destroy the uinput device and close.
    void destroy() = 16777114;
}
