package com.weather.metro.ui.tools

import android.os.Handler
import android.os.Looper
import org.maplibre.android.maps.MapView

private const val MAPLIBRE_DESTROY_GRACE_MS = 600L

/**
 * MapLibre tears down native renderer state synchronously. Running that work while Compose is
 * animating a destination change drops visible frames, so wait until the transition has settled
 * and the main queue is idle. Pause/stop must still be called by the owner before scheduling this.
 */
internal fun MapView.destroyAfterToolTransition() {
    Handler(Looper.getMainLooper()).postDelayed(
        {
            Looper.getMainLooper().queue.addIdleHandler {
                onDestroy()
                false
            }
        },
        MAPLIBRE_DESTROY_GRACE_MS,
    )
}
