package com.jad.tvglow;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * TV memory readings, and closing apps that sit idle in the background.
 *
 * Android doesn't let one app force-stop another. What it does allow is asking it to close an
 * app's background processes: apps that are idle get closed and simply start fresh next time,
 * while anything on screen, playing or bound by the system is left alone by Android itself.
 */
final class TvHealth {
    private TvHealth() {}

    /** Never closed: app stores and Google services would only restart and churn. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf"));

    static final class Memory {
        final long freeMb, totalMb;
        final boolean low;

        Memory(long freeMb, long totalMb, boolean low) {
            this.freeMb = freeMb;
            this.totalMb = totalMb;
            this.low = low;
        }
    }

    /** Current memory, or null if the TV won't say. */
    static Memory memory(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return new Memory(mi.availMem >> 20, mi.totalMem >> 20, mi.lowMemory);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Asks Android to close the background processes of every TV app except the home screen,
     * keyboards, app store, Google services and TV Glow.
     *
     * @return how many apps were asked to close
     */
    static int closeBackgroundApps(Context c) {
        PackageManager pm = c.getPackageManager();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> keep = new HashSet<>(KEEP);
        keep.add(c.getPackageName());
        for (ResolveInfo r : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            keep.add(r.activityInfo.packageName);
        }
        try {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : imm.getInputMethodList()) keep.add(i.getPackageName());
        } catch (Throwable ignored) {
            // no keyboard list: nothing else to protect
        }
        Set<String> apps = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                apps.add(r.activityInfo.packageName);
            }
        }
        int asked = 0;
        for (String p : apps) {
            if (keep.contains(p)) continue;
            try {
                am.killBackgroundProcesses(p);
                asked++;
            } catch (Throwable ignored) {
                // skip anything Android refuses
            }
        }
        return asked;
    }
}
