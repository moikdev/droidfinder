package dev.findroid.qa;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Shell-only acceptance helper: Android 11's generic input tap has an unknown tool type. */
public final class NativeTap {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected screen x and y");
        float x = Float.parseFloat(args[0]);
        float y = Float.parseFloat(args[1]);
        Class<?> managerType = Class.forName("android.hardware.input.InputManager");
        Object manager = managerType.getMethod("getInstance").invoke(null);
        Method inject = managerType.getMethod("injectInputEvent", InputEvent.class, int.class);
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0;
        property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
        coordinate.x = x;
        coordinate.y = y;
        coordinate.size = 1f;
        long downTime = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            coordinate.pressure = action == MotionEvent.ACTION_DOWN ? 1f : 0f;
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                    new MotionEvent.PointerProperties[] {property},
                    new MotionEvent.PointerCoords[] {coordinate}, 0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                if (!Boolean.TRUE.equals(inject.invoke(manager, event, 2))) {
                    throw new IllegalStateException("Android refused the native finger event");
                }
            } finally {
                event.recycle();
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40);
        }
    }
}
