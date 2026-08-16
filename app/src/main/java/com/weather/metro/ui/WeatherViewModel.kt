package com.weather.metro.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.weather.metro.data.RefreshResult
import com.weather.metro.data.WeatherRepository
import com.weather.metro.data.cache.WeatherCache
import com.weather.metro.data.hko.HkoClient
import com.weather.metro.data.location.LocationRepository
import com.weather.metro.data.settings.PageColourSlot
import com.weather.metro.data.settings.SettingsRepository
import com.weather.metro.data.settings.UiSettings
import com.weather.metro.domain.LocationInfo
import com.weather.metro.domain.WeatherLoadState
import com.weather.metro.notification.LocationHeavyRainScheduler
import com.weather.metro.notification.NotificationChannels
import com.weather.metro.notification.NotificationJournalState
import com.weather.metro.notification.NotificationReconcileScheduler
import com.weather.metro.notification.PersonalizedNotificationLocationStore
import com.weather.metro.notification.shouldSchedulePersonalizedLocationNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppNavigationRequest(
    val page: PageColourSlot,
    val showAlerts: Boolean,
    val alertId: String?,
    val alertCode: String?,
    val eventKind: String?,
    val token: Long = System.nanoTime(),
)

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val locationRepository = LocationRepository(application)
    private val personalizedLocationStore = PersonalizedNotificationLocationStore(application)
    private val weatherRepository = WeatherRepository(
        hkoClient = HkoClient(),
        locationRepository = locationRepository,
        cache = WeatherCache(application),
    )

    private val _loadState = MutableStateFlow<WeatherLoadState>(WeatherLoadState.Loading)
    val loadState: StateFlow<WeatherLoadState> = _loadState.asStateFlow()
    private val _toolLocation = MutableStateFlow<LocationInfo?>(null)
    val toolLocation: StateFlow<LocationInfo?> = _toolLocation.asStateFlow()
    val settings: StateFlow<UiSettings> = settingsRepository.settings
    private val _navigationRequest = MutableStateFlow<AppNavigationRequest?>(null)
    val navigationRequest: StateFlow<AppNavigationRequest?> = _navigationRequest.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = weatherRepository.cached()
            if (cached != null) {
                _toolLocation.value = cached.location
                _loadState.value = WeatherLoadState.Ready(cached, refreshing = true)
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val existing = (_loadState.value as? WeatherLoadState.Ready)?.snapshot
            if (existing != null) _loadState.value = WeatherLoadState.Ready(existing, refreshing = true)
            else _loadState.value = WeatherLoadState.Loading

            val location = weatherRepository.resolveLocation(settings.value.preciseLocation)
            _toolLocation.value = location
            bindPersonalizedNotificationLocation(location)
            runCatching {
                weatherRepository.refreshAt(location)
            }.onSuccess(::showResult).onFailure { error ->
                _loadState.value = WeatherLoadState.Error(
                    message = error.message ?: "未能取得天氣資料",
                    cached = existing,
                )
            }
        }
    }

    fun hasLocationPermission(): Boolean = weatherRepository.hasLocationPermission()

    fun setPageColour(slot: PageColourSlot, value: Long) = settingsRepository.setPageColour(slot, value)
    fun setTextScale(value: Float) = settingsRepository.setTextScale(value)
    fun setReduceMotion(value: Boolean) = settingsRepository.setReduceMotion(value)
    fun setHighContrast(value: Boolean) = settingsRepository.setHighContrast(value)

    fun setPreciseLocation(value: Boolean) {
        settingsRepository.setPreciseLocation(value)
        val application = getApplication<Application>()
        if (!value) {
            personalizedLocationStore.clear()
            LocationHeavyRainScheduler.resetAll(application)
        }
        refresh()
    }

    fun setNotificationsEnabled(value: Boolean) {
        settingsRepository.setNotificationsEnabled(value)
        val application = getApplication<Application>()
        val messaging = FirebaseMessaging.getInstance()
        if (value) {
            messaging.subscribeToTopic(NotificationChannels.TOPIC_PRODUCTION)
            NotificationReconcileScheduler.ensurePeriodic(application)
            NotificationReconcileScheduler.enqueueNow(application)
            reconcilePersonalizedLocationNotificationSchedule(enqueueNow = true)
        } else {
            messaging.unsubscribeFromTopic(NotificationChannels.TOPIC_PRODUCTION)
            NotificationReconcileScheduler.disable(application)
            NotificationJournalState(application).resetSubscriptionBaseline()
            LocationHeavyRainScheduler.resetAll(application)
        }
    }

    fun setLocationHeavyRainNotificationsEnabled(value: Boolean) {
        settingsRepository.setLocationHeavyRainNotificationsEnabled(value)
        val application = getApplication<Application>()
        if (!value) LocationHeavyRainScheduler.resetHeavyRain(application)
        reconcilePersonalizedLocationNotificationSchedule(enqueueNow = value)
    }

    fun setPersonalizedRainNotificationsEnabled(value: Boolean) {
        settingsRepository.setPersonalizedRainNotificationsEnabled(value)
        val application = getApplication<Application>()
        if (!value) LocationHeavyRainScheduler.resetPersonalizedRain(application)
        reconcilePersonalizedLocationNotificationSchedule(enqueueNow = value)
    }

    fun subscribeIfEnabled() {
        if (settings.value.notificationsEnabled) {
            val application = getApplication<Application>()
            FirebaseMessaging.getInstance().subscribeToTopic(NotificationChannels.TOPIC_PRODUCTION)
            NotificationReconcileScheduler.ensurePeriodic(application)
            NotificationReconcileScheduler.enqueueNow(application)
            reconcilePersonalizedLocationNotificationSchedule(enqueueNow = true)
        }
    }

    fun handleDeepLink(uri: Uri?) {
        if (uri?.scheme != "weathermetro") return
        val page = PageColourSlot.entries.firstOrNull { it.label == uri.host }
            ?: PageColourSlot.CURRENT
        _navigationRequest.value = AppNavigationRequest(
            page = page,
            showAlerts = uri.pathSegments.firstOrNull() == "alerts",
            alertId = uri.getQueryParameter("alertId"),
            alertCode = uri.getQueryParameter("code"),
            eventKind = uri.getQueryParameter("kind")?.uppercase(),
        )
        refresh()
    }

    fun consumeNavigation(token: Long) {
        if (_navigationRequest.value?.token == token) _navigationRequest.value = null
    }

    fun clearCache() {
        viewModelScope.launch {
            weatherRepository.clearCache()
            refresh()
        }
    }

    private fun bindPersonalizedNotificationLocation(location: LocationInfo) {
        if (!settings.value.preciseLocation || location.accuracyMetres == null) return
        personalizedLocationStore.record(location)
        reconcilePersonalizedLocationNotificationSchedule(enqueueNow = true)
    }

    private fun reconcilePersonalizedLocationNotificationSchedule(enqueueNow: Boolean) {
        val current = settings.value
        val application = getApplication<Application>()
        if (
            shouldSchedulePersonalizedLocationNotifications(
                notificationsEnabled = current.notificationsEnabled,
                preciseLocationEnabled = current.preciseLocation,
                locationHeavyRainEnabled = current.locationHeavyRainNotificationsEnabled,
                personalizedRainEnabled = current.personalizedRainNotificationsEnabled,
            )
        ) {
            LocationHeavyRainScheduler.ensurePeriodic(application)
            if (enqueueNow) LocationHeavyRainScheduler.enqueueNow(application)
        } else {
            LocationHeavyRainScheduler.disable(application)
        }
    }

    private fun showResult(result: RefreshResult) {
        _loadState.value = WeatherLoadState.Ready(result.snapshot)
    }
}
