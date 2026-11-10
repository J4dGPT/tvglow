package com.jad.tvglow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the companion (phone remote, lights off with the TV) when the TV boots or the app updates. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        RemoteService.start(c);
    }
}
