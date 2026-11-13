package moe.cyunrei.videolivewallpaper.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Nothing to start here: the system restarts the live wallpaper by itself after boot.
// The wallpaper service now retries loading the video until storage is ready.
internal class BootBroadCast : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // intentionally empty (the old startService call could crash on boot)
    }
}
