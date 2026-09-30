package io.homeassistant.companion.android.sensors

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.UpdateLocation
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.sensors.SensorManager
import io.homeassistant.companion.android.common.sensors.SensorReceiverBase
import io.homeassistant.companion.android.common.util.DisabledLocationHandler
import io.homeassistant.companion.android.database.location.LocationHistoryDao
import io.homeassistant.companion.android.database.location.LocationHistoryItem
import io.homeassistant.companion.android.database.location.LocationHistoryItemResult
import io.homeassistant.companion.android.database.location.LocationHistoryItemTrigger
import io.homeassistant.companion.android.database.sensor.Attribute
import io.homeassistant.companion.android.database.sensor.SensorSetting
import io.homeassistant.companion.android.database.sensor.SensorSettingType
import io.homeassistant.companion.android.database.sensor.toSensorWithAttributes
import io.homeassistant.companion.android.location.HighAccuracyLocationService
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class LocationSensorManager :
    BroadcastReceiver(),
    SensorManager {

    companion object {
        private const val SETTING_ACCURACY = "location_minimum_accuracy"
        private const val SETTING_ACCURATE_UPDATE_TIME = "location_minimum_time_updates"
        private const val SETTING_INCLUDE_SENSOR_UPDATE = "location_include_sensor_update"

        private const val DEFAULT_MINIMUM_ACCURACY = 200
        private const val HISTORY_DURATION = 60 * 60 * 48 * 1000L

        const val MINIMUM_ACCURACY = DEFAULT_MINIMUM_ACCURACY

        const val ACTION_REQUEST_LOCATION_UPDATES =
            "io.homeassistant.companion.android.background.REQUEST_UPDATES"
        const val ACTION_REQUEST_ACCURATE_LOCATION_UPDATE =
            "io.homeassistant.companion.android.background.REQUEST_ACCURATE_UPDATE"
        const val ACTION_PROCESS_LOCATION =
            "io.homeassistant.companion.android.background.PROCESS_UPDATES"
        const val ACTION_PROCESS_GEO =
            "io.homeassistant.companion.android.background.PROCESS_GEOFENCE"
        const val ACTION_FORCE_HIGH_ACCURACY =
            "io.homeassistant.companion.android.background.FORCE_HIGH_ACCURACY"

        private const val EXTRA_LATITUDE = "latitude"
        private const val EXTRA_LONGITUDE = "longitude"
        private const val EXTRA_ACCURACY = "accuracy"
        private const val EXTRA_ALTITUDE = "altitude"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_BEARING = "bearing"
        private const val EXTRA_TIME = "time"

        val backgroundLocation = SensorManager.BasicSensor(
            "location_background",
            "",
            commonR.string.basic_sensor_name_location_background,
            commonR.string.sensor_description_location_background,
            "mdi:map-marker-multiple",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
        )
        val zoneLocation = SensorManager.BasicSensor(
            "zone_background",
            "",
            commonR.string.basic_sensor_name_location_zone,
            commonR.string.sensor_description_location_zone,
            "mdi:map-marker-radius",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
        )
        val singleAccurateLocation = SensorManager.BasicSensor(
            "accurate_location",
            "",
            commonR.string.basic_sensor_name_location_accurate,
            commonR.string.sensor_description_location_accurate,
            "mdi:crosshairs-gps",
            updateType = SensorManager.BasicSensor.UpdateType.LOCATION,
        )

        fun setHighAccuracyModeSetting(context: Context, enabled: Boolean) {
            // No high-accuracy mode configuration in minimal
        }

        fun setHighAccuracyModeIntervalSetting(context: Context, updateInterval: Int) {
            // No high-accuracy mode configuration in minimal
        }

        /**
         * Builds an explicit-component [Intent] addressed to this receiver that triggers a single
         * accurate location update via [ACTION_REQUEST_ACCURATE_LOCATION_UPDATE].
         */
        fun createRequestAccurateLocationUpdateIntent(context: Context): Intent = Intent(
            context,
            LocationSensorManager::class.java,
        ).apply {
            action = ACTION_REQUEST_ACCURATE_LOCATION_UPDATE
        }

        /**
         * Creates an [Intent] containing location data to be sent back from the foreground service.
         */
        fun createLocationUpdateIntent(context: Context, location: Location): Intent = Intent(
            context,
            LocationSensorManager::class.java,
        ).apply {
            action = ACTION_PROCESS_LOCATION
            putExtra(EXTRA_LATITUDE, location.latitude)
            putExtra(EXTRA_LONGITUDE, location.longitude)
            putExtra(EXTRA_ACCURACY, location.accuracy)
            putExtra(EXTRA_ALTITUDE, location.altitude)
            putExtra(EXTRA_SPEED, location.speed)
            putExtra(EXTRA_BEARING, location.bearing)
            putExtra(EXTRA_TIME, location.time)
        }

        private var lastLocationSend = mutableMapOf<Int, Long>()
        private var lastUpdateLocation = mutableMapOf<Int, String?>()
    }

    @Inject
    lateinit var prefsRepository: PrefsRepository

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    lateinit var latestContext: Context

    override fun onReceive(context: Context, intent: Intent) {
        latestContext = context

        sensorWorkerScope.launch {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                ACTION_REQUEST_LOCATION_UPDATES,
                -> setupLocationTracking()

                ACTION_PROCESS_LOCATION -> handleLocationUpdate(intent)
                ACTION_REQUEST_ACCURATE_LOCATION_UPDATE -> requestSingleAccurateLocation()
                else -> Timber.w("Unknown intent action: ${intent.action}")
            }
        }
    }

    private suspend fun setupLocationTracking() {
        if (!checkPermission(latestContext, backgroundLocation.id)) {
            Timber.w("Not starting location reporting because of permissions")
            return
        }

        val backgroundEnabled = isEnabled(latestContext, backgroundLocation)

        if (backgroundEnabled) {
            Timber.d("Starting native location service for background tracking")
            HighAccuracyLocationService.startService(latestContext)
        } else {
            Timber.d("Background location disabled, stopping service")
            HighAccuracyLocationService.stopService(latestContext)
        }
    }

    private suspend fun handleLocationUpdate(intent: Intent) {
        Timber.d("Received location update from native provider")
        val serverIds = getEnabledServers(latestContext, backgroundLocation)
        if (serverIds.isEmpty()) return

        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        val accuracy = intent.getFloatExtra(EXTRA_ACCURACY, 0f)
        val altitude = intent.getDoubleExtra(EXTRA_ALTITUDE, 0.0)
        val speed = intent.getFloatExtra(EXTRA_SPEED, 0f)
        val bearing = intent.getFloatExtra(EXTRA_BEARING, 0f)
        val time = intent.getLongExtra(EXTRA_TIME, 0L)

        if (latitude == 0.0 && longitude == 0.0) {
            Timber.w("Received empty location, ignoring")
            return
        }

        val location = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            this.accuracy = accuracy
            this.altitude = altitude
            this.speed = speed
            this.bearing = bearing
            this.time = time
        }

        val sensorDao = sensorDao(latestContext)
        val sensorSettings = sensorDao.getSettings(backgroundLocation.id)
        val minAccuracy = sensorSettings
            .firstOrNull { it.name == SETTING_ACCURACY }?.value?.toIntOrNull()
            ?: DEFAULT_MINIMUM_ACCURACY
        sensorDao.add(
            SensorSetting(
                backgroundLocation.id,
                SETTING_ACCURACY,
                minAccuracy.toString(),
                SensorSettingType.NUMBER,
            ),
        )

        if (location.accuracy > minAccuracy) {
            Timber.w("Location accuracy didn't meet requirements, disregarding: $location")
            logLocationUpdate(location, null, null, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.SKIPPED_ACCURACY)
            return
        }

        serverIds.forEach { serverId ->
            ioScope.launch { sendLocationUpdate(location, serverId) }
        }
    }

    private suspend fun sendLocationUpdate(location: Location, serverId: Int) {
        Timber.d(
            "Last Location:" +
                "\nCoords:(${location.latitude}, ${location.longitude})" +
                "\nAccuracy: ${location.accuracy}" +
                "\nBearing: ${location.bearing}",
        )

        var accuracy = 0
        if (location.accuracy.toInt() >= 0) {
            accuracy = location.accuracy.toInt()
        }

        val updateLocation = UpdateLocation(
            gps = listOf(location.latitude, location.longitude),
            gpsAccuracy = accuracy,
            locationName = null,
            inZones = null,
            speed = location.speed.toInt(),
            altitude = location.altitude.toInt(),
            course = location.bearing.toInt(),
            verticalAccuracy = 0,
        )
        val updateLocationString = updateLocation.gps.toString()

        val now = System.currentTimeMillis()

        if (now + 5000 < location.time) {
            Timber.d("Skipping location update that came from the future")
            logLocationUpdate(location, updateLocation, serverId, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.SKIPPED_FUTURE)
            return
        }

        if (location.time < (lastLocationSend[serverId] ?: 0)) {
            Timber.d("Skipping old location update since time is before the last one we sent")
            logLocationUpdate(location, updateLocation, serverId, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.SKIPPED_NOT_LATEST)
            return
        }

        if (now - location.time < 300000) {
            if (lastUpdateLocation[serverId] == updateLocationString) {
                if (now < (lastLocationSend[serverId] ?: 0) + 900000) {
                    Timber.d("Duplicate location received, not sending to HA")
                    logLocationUpdate(location, updateLocation, serverId, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.SKIPPED_DUPLICATE)
                    return
                }
            } else {
                if (now < (lastLocationSend[serverId] ?: 0) + 5000) {
                    Timber.d("New location update not possible within 5 seconds, not sending to HA")
                    logLocationUpdate(location, updateLocation, serverId, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.SKIPPED_DEBOUNCE)
                    return
                }
            }
        } else {
            Timber.d("Skipping location update due to old timestamp ${location.time} compared to $now")
            logLocationUpdate(location, updateLocation, serverId, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.SKIPPED_OLD)
            return
        }

        val geocodeIncludeLocation = getSetting(
            latestContext,
            GeocodeSensorManager.geocodedLocation,
            GeocodeSensorManager.SETTINGS_INCLUDE_LOCATION,
            SensorSettingType.TOGGLE,
            "false",
        ).toBoolean()

        ioScope.launch {
            try {
                serverManager(latestContext).integrationRepository(serverId).updateLocation(updateLocation)
                Timber.d("Location update sent successfully for server $serverId")
                lastLocationSend[serverId] = now
                lastUpdateLocation[serverId] = updateLocationString
                logLocationUpdate(location, updateLocation, serverId, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.SENT)

                if (geocodeIncludeLocation) {
                    val intent = Intent(latestContext, SensorReceiver::class.java)
                    intent.action = SensorReceiverBase.ACTION_UPDATE_SENSOR
                    intent.putExtra(
                        SensorReceiverBase.EXTRA_SENSOR_ID,
                        GeocodeSensorManager.geocodedLocation.id,
                    )
                    latestContext.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Could not update location for server $serverId")
                logLocationUpdate(location, updateLocation, serverId, LocationHistoryItemTrigger.FLP_BACKGROUND, LocationHistoryItemResult.FAILED_SEND)
            }
        }
    }

    private suspend fun requestSingleAccurateLocation() {
        if (!checkPermission(latestContext, singleAccurateLocation.id)) {
            Timber.w("Not getting single accurate location because of permissions")
            return
        }
        if (!isEnabled(latestContext, singleAccurateLocation)) {
            Timber.w("Requested single accurate location but it is not enabled")
            return
        }

        val now = System.currentTimeMillis()
        val sensorDao = sensorDao(latestContext)
        val fullSensor = sensorDao.getFull(singleAccurateLocation.id).toSensorWithAttributes()
        val latestAccurateLocation =
            fullSensor?.attributes?.firstOrNull { it.name == "lastAccurateLocationRequest" }?.value?.toLongOrNull()
                ?: 0L

        val sensorSettings = sensorDao.getSettings(singleAccurateLocation.id)
        val minAccuracy = sensorSettings
            .firstOrNull { it.name == SETTING_ACCURACY }?.value?.toIntOrNull()
            ?: DEFAULT_MINIMUM_ACCURACY
        sensorDao.add(
            SensorSetting(
                singleAccurateLocation.id,
                SETTING_ACCURACY,
                minAccuracy.toString(),
                SensorSettingType.NUMBER,
            ),
        )
        val minTimeBetweenUpdates = sensorSettings
            .firstOrNull { it.name == SETTING_ACCURATE_UPDATE_TIME }?.value?.toIntOrNull()
            ?: 60000
        sensorDao.add(
            SensorSetting(
                singleAccurateLocation.id,
                SETTING_ACCURATE_UPDATE_TIME,
                minTimeBetweenUpdates.toString(),
                SensorSettingType.NUMBER,
            ),
        )

        if (now < latestAccurateLocation + minTimeBetweenUpdates) {
            Timber.d("Not requesting accurate location, last accurate location was too recent")
            return
        }
        sensorDao.add(Attribute(singleAccurateLocation.id, "lastAccurateLocationRequest", now.toString(), "string"))

        val locationManager = latestContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Timber.w("Couldn't get LocationManager for single accurate location")
            return
        }

        val wakeLock = latestContext.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeAssistant::AccurateLocation")
            ?.apply { acquire(2 * 60 * 1000L) }

        try {
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                LocationManager.GPS_PROVIDER,
                CancellationSignal(),
                ContextCompat.getMainExecutor(latestContext),
            ) { location ->
                if (location != null && location.accuracy <= minAccuracy) {
                    Timber.d("Got single accurate location: $location")
                    ioScope.launch {
                        getEnabledServers(latestContext, singleAccurateLocation).forEach { serverId ->
                            sendLocationUpdate(location, serverId)
                        }
                    }
                } else {
                    Timber.w("Single accurate location was null or not accurate enough: $location")
                }
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to get single accurate location due to permission issue")
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    override fun docsLink(): String {
        return "https://companion.home-assistant.io/docs/core/location"
    }

    override val name: Int
        get() = commonR.string.sensor_name_location

    override suspend fun getAvailableSensors(context: Context): List<SensorManager.BasicSensor> {
        return if (DisabledLocationHandler.hasGPS(context)) {
            listOf(backgroundLocation, singleAccurateLocation)
        } else {
            listOf(backgroundLocation)
        }
    }

    override fun requiredPermissions(context: Context, sensorId: String): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    override suspend fun requestSensorUpdate(context: Context) {
        latestContext = context
        if (isEnabled(context, backgroundLocation)) {
            setupLocationTracking()
        }
        cleanupLocationHistory(context)
        val sensorDao = sensorDao(latestContext)
        val sensorSetting = sensorDao.getSettings(singleAccurateLocation.id)
        val includeSensorUpdate =
            sensorSetting.firstOrNull { it.name == SETTING_INCLUDE_SENSOR_UPDATE }?.value ?: "false"
        if (includeSensorUpdate == "true") {
            if (isEnabled(context, singleAccurateLocation)) {
                context.sendBroadcast(createRequestAccurateLocationUpdateIntent(context))
            }
        } else {
            sensorDao.add(
                SensorSetting(
                    singleAccurateLocation.id,
                    SETTING_INCLUDE_SENSOR_UPDATE,
                    "false",
                    SensorSettingType.TOGGLE,
                ),
            )
        }
    }

    private fun cleanupLocationHistory(context: Context) = ioScope.launch {
        handleInject(context)
        val historyDao = locationSensorManagerEntryPoint(context).locationHistoryDao()
        val historyEnabled = prefsRepository.isLocationHistoryEnabled()
        if (historyEnabled) {
            historyDao.deleteBefore(System.currentTimeMillis() - HISTORY_DURATION)
        } else {
            historyDao.deleteAll()
        }
    }

    private fun logLocationUpdate(
        location: Location?,
        updateLocation: UpdateLocation?,
        serverId: Int?,
        trigger: LocationHistoryItemTrigger,
        result: LocationHistoryItemResult,
    ) = ioScope.launch {
        if (location == null || !prefsRepository.isLocationHistoryEnabled()) return@launch

        try {
            locationSensorManagerEntryPoint(latestContext).locationHistoryDao().add(
                LocationHistoryItem(
                    trigger = trigger,
                    result = result,
                    latitude = if (updateLocation != null) updateLocation.gps?.getOrNull(0) else location.latitude,
                    longitude = if (updateLocation != null) updateLocation.gps?.getOrNull(1) else location.longitude,
                    locationName = updateLocation?.locationName,
                    inZones = updateLocation?.inZones.orEmpty(),
                    accuracy = updateLocation?.gpsAccuracy ?: location.accuracy.toInt(),
                    data = updateLocation?.toString(),
                    serverId = serverId,
                ),
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to log location update")
        }
    }

    private fun locationSensorManagerEntryPoint(context: Context): LocationSensorManagerEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            LocationSensorManagerEntryPoint::class.java,
        )

    private fun handleInject(context: Context) {
        if (!this::prefsRepository.isInitialized) {
            prefsRepository = locationSensorManagerEntryPoint(context).prefsRepository()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LocationSensorManagerEntryPoint {
        fun prefsRepository(): PrefsRepository
        fun locationHistoryDao(): LocationHistoryDao
    }
}
