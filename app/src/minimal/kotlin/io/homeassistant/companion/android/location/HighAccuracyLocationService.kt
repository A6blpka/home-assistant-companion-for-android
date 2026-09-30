package io.homeassistant.companion.android.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.util.CHANNEL_HIGH_ACCURACY
import io.homeassistant.companion.android.sensors.LocationSensorManager
import io.homeassistant.companion.android.util.ForegroundServiceLauncher
import kotlin.math.abs
import kotlin.math.roundToInt
import timber.log.Timber

class HighAccuracyLocationService : Service() {

    companion object {
        private const val EXTRA_INTERVAL_IN_SECONDS = "intervalInSeconds"
        private const val DEFAULT_UPDATE_INTERVAL_SECONDS = 5

        private val LAUNCHER = ForegroundServiceLauncher(HighAccuracyLocationService::class.java)

        const val HIGH_ACCURACY_LOCATION_NOTIFICATION_ID = "HighAccuracyLocationNotification"

        @Synchronized
        fun startService(context: Context, intervalInSeconds: Int) {
            Timber.d("Try starting native location service (interval=${intervalInSeconds}s)...")
            LAUNCHER.startService(context) {
                putExtra(EXTRA_INTERVAL_IN_SECONDS, intervalInSeconds)
            }
        }

        @Synchronized
        fun stopService(context: Context) {
            Timber.d("Try stopping native location service...")
            LAUNCHER.stopService(context)
        }

        @Synchronized
        fun restartService(context: Context, intervalInSeconds: Int) {
            Timber.d("Try restarting native location service (interval=${intervalInSeconds}s)...")
            LAUNCHER.restartService(context) {
                putExtra(EXTRA_INTERVAL_IN_SECONDS, intervalInSeconds)
            }
        }

        fun updateNotificationAddress(context: Context, location: Location, geocodedAddress: String = "") {
            var locationReadable = geocodedAddress
            if (locationReadable.isEmpty()) {
                locationReadable = getFormattedLocationInDegree(location.latitude, location.longitude)
            }
            locationReadable = "$locationReadable (~${location.accuracy}m)"

            updateNotificationContentText(context, locationReadable)
        }

        private fun getFormattedLocationInDegree(latitude: Double, longitude: Double): String {
            return try {
                var latSeconds = (latitude * 3600).roundToInt()
                val latDegrees = latSeconds / 3600
                latSeconds = abs(latSeconds % 3600)
                val latMinutes = latSeconds / 60
                latSeconds %= 60
                var longSeconds = (longitude * 3600).roundToInt()
                val longDegrees = longSeconds / 3600
                longSeconds = abs(longSeconds % 3600)
                val longMinutes = longSeconds / 60
                longSeconds %= 60
                val latDegree = if (latDegrees >= 0) "N" else "S"
                val lonDegrees = if (longDegrees >= 0) "E" else "W"
                (
                    abs(latDegrees).toString() + "\u00B0" + latMinutes + "'" + latSeconds +
                        "\"" + latDegree + " " + abs(longDegrees) + "\u00B0" + longMinutes +
                        "'" + longSeconds + "\"" + lonDegrees
                    )
            } catch (e: Exception) {
                "" + String.format("%8.5f", latitude) + "  " +
                    String.format("%8.5f", longitude)
            }
        }

        private lateinit var notificationBuilder: NotificationCompat.Builder

        private fun updateNotificationContentText(context: Context, text: String) {
            if (LAUNCHER.isRunning()) {
                val notificationManager = NotificationManagerCompat.from(context)
                val notificationId = HIGH_ACCURACY_LOCATION_NOTIFICATION_ID.hashCode()
                notificationBuilder
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                notificationManager.notify(notificationId, notificationBuilder.build())
            }
        }
    }

    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Timber.d("Native GPS location received: (${location.latitude}, ${location.longitude}) accuracy=${location.accuracy}")
            val intent = LocationSensorManager.createLocationUpdateIntent(this@HighAccuracyLocationService, location)
            sendBroadcast(intent)
        }

        @Deprecated("Deprecated in API 29, but needed for API 28")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            Timber.d("Location provider status changed: $provider -> $status")
        }

        override fun onProviderEnabled(provider: String) {
            Timber.d("Location provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Timber.d("Location provider disabled: $provider")
        }
    }

    override fun onCreate() {
        super.onCreate()

        val notificationId = HIGH_ACCURACY_LOCATION_NOTIFICATION_ID.hashCode()
        val notificationManagerCompat = NotificationManagerCompat.from(this)

        val channel = NotificationChannel(
            CHANNEL_HIGH_ACCURACY,
            getString(commonR.string.high_accuracy_mode_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManagerCompat.createNotificationChannel(channel)

        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_HIGH_ACCURACY)
            .setSmallIcon(commonR.drawable.ic_stat_ic_notification)
            .setColor(Color.GRAY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(commonR.string.high_accuracy_mode_notification_title))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_SERVICE)

        val notification = notificationBuilder.build()

        LAUNCHER.onServiceCreated(this, notificationId, notification, 0)

        Timber.d("Native location service created")
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val intervalInSeconds = intent?.getIntExtra(
            EXTRA_INTERVAL_IN_SECONDS,
            DEFAULT_UPDATE_INTERVAL_SECONDS,
        ) ?: DEFAULT_UPDATE_INTERVAL_SECONDS
        requestLocationUpdates(intervalInSeconds * 1000L)

        Timber.d("Native location service started (interval=${intervalInSeconds}s)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        locationManager?.removeUpdates(locationListener)
        locationManager = null

        LAUNCHER.onServiceDestroy(this)

        NotificationManagerCompat.from(this).cancel(HIGH_ACCURACY_LOCATION_NOTIFICATION_ID.hashCode())

        Timber.d("Native location service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(intervalMs: Long) {
        locationManager?.removeUpdates(locationListener)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Timber.e("LocationManager not available, stopping service")
            stopSelf()
            return
        }

        val lm = locationManager!!

        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Timber.d("Requesting GPS_PROVIDER location updates (interval=${intervalMs}ms)")
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                locationListener,
            )
        } else {
            Timber.w("GPS_PROVIDER not available")
        }

        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Timber.d("Requesting NETWORK_PROVIDER location updates as fallback (interval=${intervalMs}ms)")
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                intervalMs,
                0f,
                locationListener,
            )
        } else {
            Timber.d("NETWORK_PROVIDER not available, using GPS only")
        }
    }
}
