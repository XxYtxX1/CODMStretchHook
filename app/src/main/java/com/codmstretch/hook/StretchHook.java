package com.codmstretch.hook;

import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs inside system_server (scope: System Framework).
 *
 * When the display size is overridden to a different aspect ratio ("wm size"), Android keeps
 * the picture's shape and centers it with black bars. This hook replaces that centered display
 * rect with the full panel, so SurfaceFlinger stretches the picture to fill the screen.
 *
 * The projection method name differs between Android versions, so the known names are tried
 * first, then any "projection" method that takes two Rect arguments. The status file says which
 * method was hooked (msg=), the signature of the first call (sig=), and why the last call was
 * skipped (skip=).
 */
public class StretchHook implements IXposedHookLoadPackage {
    private static final String STATUS_FILE = "/data/system/codm_stretch_hook.txt";
    private static final String OFF_FILE = "/data/system/codm_stretch_off";
    private static final String TARGET = "com.android.server.display.DisplayDevice";
    // Newer trees: setProjectionLocked(SurfaceControl.Transaction, int, Rect, Rect).
    // Older trees: setProjectionInTransactionLocked(int, Rect, Rect).
    private static final String[] NAMES = {
            "setProjectionLocked", "setProjectionInTransactionLocked",
            "setProjectionInTransaction", "setProjection"
    };

    private static volatile boolean hooked = false;
    private static volatile String message = "starting";
    private static volatile String sig = "";
    private static volatile String skipReason = "none yet";
    private static volatile String last = "";
    private static volatile int events = 0;
    private static volatile long lastWrite = 0;
    private static volatile String bootId = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !"android".equals(lp.packageName)) return;
        try {
            bootId = firstLine("/proc/sys/kernel/random/boot_id");
            Class<?> cls = XposedHelpers.findClassIfExists(TARGET, lp.classLoader);
            if (cls == null) {
                message = "DisplayDevice class not found";
            } else {
                install(cls);
            }
        } catch (Throwable t) {
            message = "load error " + t.getClass().getSimpleName();
            XposedBridge.log(t);
        }
        writeStatus(true);
    }

    // Hooks the known projection names, then any other "projection" method with two Rect args.
    static void install(Class<?> cls) {
        Set<String> done = new HashSet<>();
        List<String> report = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int count = 0;
        for (String name : NAMES) {
            int n = hookName(cls, name);
            if (n > 0) {
                done.add(name);
                report.add(name);
                count += n;
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            String name = m.getName();
            if (name.toLowerCase(Locale.US).indexOf("projection") < 0) continue;
            if (!seen.contains(name)) seen.add(name);
            if (done.contains(name) || rectCount(m) < 2) continue;
            int n = hookName(cls, name);
            if (n > 0) {
                done.add(name);
                report.add("discovered " + name);
                count += n;
            }
        }
        hooked = count > 0;
        if (hooked) {
            message = "hooked " + String.join(", ", report) + " x" + count;
        } else {
            message = "no projection method matched; seen: "
                    + (seen.isEmpty() ? "none" : String.join(", ", seen));
        }
    }

    private static int hookName(Class<?> cls, String name) {
        Set<?> set = XposedBridge.hookAllMethods(cls, name, new ProjectionHook());
        return set == null ? 0 : set.size();
    }

    private static int rectCount(Method m) {
        int n = 0;
        for (Class<?> c : m.getParameterTypes()) {
            if (c == Rect.class) n++;
        }
        return n;
    }

    // The bars are centered, so the full panel is the rect plus its offsets.
    // Returns null when there is nothing to fill.
    static Rect fullRect(Rect disp) {
        if (disp.left <= 0 && disp.top <= 0) return null;
        int w = disp.right + disp.left;
        int h = disp.bottom + disp.top;
        if (w <= 0 || h <= 0) return null;
        return new Rect(0, 0, w, h);
    }

    private static boolean isInternal(Object device) {
        try {
            Object info = XposedHelpers.callMethod(device, "getDisplayDeviceInfoLocked");
            return XposedHelpers.getIntField(info, "type") == 1; // Display.TYPE_INTERNAL
        } catch (Throwable t) {
            return true;
        }
    }

    private static String describe(Member method, Object[] a) {
        StringBuilder sb = new StringBuilder(method == null ? "?" : method.getName()).append('(');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i] == null ? "null" : a[i].getClass().getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static void outcome(String why) {
        if (!why.equals(skipReason)) {
            skipReason = why;
            writeStatus(true);
        }
    }

    private static final class ProjectionHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                if (sig.isEmpty()) {
                    sig = describe(param.method, a);
                    writeStatus(true);
                }
                if (new File(OFF_FILE).exists()) {
                    outcome("off");
                    return;
                }
                // The display rect is the second Rect argument (the first is the layer stack rect).
                int second = -1;
                int rects = 0;
                for (int i = 0; i < a.length && second < 0; i++) {
                    if (a[i] instanceof Rect && ++rects == 2) second = i;
                }
                if (second < 0) {
                    outcome("no-rects");
                    return;
                }
                if (!isInternal(param.thisObject)) {
                    outcome("not-internal");
                    return;
                }
                Rect disp = (Rect) a[second];
                Rect full = fullRect(disp);
                if (full == null) {
                    outcome(disp.left <= 0 && disp.top <= 0 ? "no-offset" : "bad-rect");
                    return;
                }
                a[second] = full;
                events++;
                last = "from " + disp.toShortString() + " to " + full.toShortString();
                outcome("applied");
                writeStatus(false);
            } catch (Throwable t) {
                outcome("error " + t.getClass().getSimpleName());
            }
        }
    }

    static String statusText(long now) {
        return "hooked=" + (hooked ? 1 : 0) + "\n"
                + "boot_id=" + bootId + "\n"
                + "epoch=" + (now / 1000) + "\n"
                + "msg=" + message + "\n"
                + "sig=" + sig + "\n"
                + "skip=" + skipReason + "\n"
                + "events=" + events + "\n"
                + "last=" + last + "\n";
    }

    private static void writeStatus(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastWrite < 2000) return;
        lastWrite = now;
        try {
            FileOutputStream out = new FileOutputStream(STATUS_FILE, false);
            try {
                out.write(statusText(now).getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // status is best-effort only
        }
    }

    private static String firstLine(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            try {
                String l = r.readLine();
                return l == null ? "" : l.trim();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }
}
