package com.weather.metro

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.weather.metro.ui.WeatherMetroRoot
import com.weather.metro.ui.WeatherViewModel
import com.weather.metro.ui.rain.RainHostViewModel
import com.weather.metro.notification.NotificationEventStore
import com.weather.metro.notification.WeatherNotificationPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: WeatherViewModel by viewModels()
    private val rainViewModel: RainHostViewModel by viewModels()
    private val notificationStore by lazy { NotificationEventStore(this) }
    private val notificationPublisher by lazy { WeatherNotificationPublisher(this, notificationStore) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refresh()
        requestNotificationPermission()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.subscribeIfEnabled()
            replayPendingNotifications()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enterImmersiveMode()
        viewModel.handleDeepLink(intent?.data)
        setContent {
            WeatherMetroRoot(
                viewModel = viewModel,
                rainViewModel = rainViewModel,
                requestLocationPermission = ::requestLocationPermission,
                requestNotificationPermission = ::requestNotificationPermission,
                openNotificationSettings = ::openNotificationSettings,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        replayPendingNotifications()
        if (notificationStore.consumeFullSyncRequired()) viewModel.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleDeepLink(intent.data)
    }

    override fun onStop() {
        rainViewModel.cancelTransientRequests()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.subscribeIfEnabled()
            replayPendingNotifications()
        }
    }

    private fun replayPendingNotifications() {
        lifecycleScope.launch(Dispatchers.IO) {
            notificationPublisher.replayPending()
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            },
        )
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
