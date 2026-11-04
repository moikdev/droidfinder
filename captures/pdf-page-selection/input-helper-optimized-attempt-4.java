package dev.findroid.qa;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Shell-only keyboard chords and finger drags for optimized PDF acceptance. */
public final class PdfSelectionInput {
    private static Object manager;
    private static Method inject;

    private static void send(InputEvent event) throws Exception {
        if (!Boolean.TRUE.equals(inject.invoke(manager, event, 2))) {
            throw new IllegalStateException("Android refused the input event");
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("android.hardware.input.InputManager");
        manager = type.getMethod("getInstance").invoke(null);
        inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        long downTime = SystemClock.uptimeMillis();
        if (args.length == 3 && args[0].equals("key")) {
            int code = Integer.parseInt(args[1]);
            int meta = Integer.parseInt(args[2]);
            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                send(new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, meta,
                        -1, 0, 0, InputDevice.SOURCE_KEYBOARD));
            }
            return;
        }
        if (args.length != 7 || !args[0].equals("drag")) {
            throw new IllegalArgumentException("Expected key code meta or drag x1 y1 x2 y2 holdMs moveMs");
        }
        float x1 = Float.parseFloat(args[1]), y1 = Float.parseFloat(args[2]);
        float x2 = Float.parseFloat(args[3]), y2 = Float.parseFloat(args[4]);
        int hold = Integer.parseInt(args[5]), duration = Integer.parseInt(args[6]);
        if (hold < 0 || hold > 2000 || duration < 0 || duration > 2000) {
            throw new IllegalArgumentException("Acceptance gesture duration is out of bounds");
        }
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
        coordinate.size = 1f; coordinate.pressure = 1f;
        int steps = Math.max(1, duration / 16);
        for (int step = -1; step <= steps + 1; step++) {
            int action = step == -1 ? MotionEvent.ACTION_DOWN :
                    step == steps + 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            float fraction = Math.max(0f, Math.min(1f, (float) step / steps));
            coordinate.x = x1 + (x2-x1)*fraction;
            coordinate.y = y1 + (y2-y1)*fraction;
            coordinate.pressure = action == MotionEvent.ACTION_UP ? 0f : 1f;
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                    new MotionEvent.PointerProperties[] {pointer}, new MotionEvent.PointerCoords[] {coordinate},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { send(event); } finally { event.recycle(); }
            if (step == -1 && hold > 0) SystemClock.sleep(hold);
            else if (step >= 0 && step < steps && duration > 0) SystemClock.sleep(duration / steps);
        }
    }
}
