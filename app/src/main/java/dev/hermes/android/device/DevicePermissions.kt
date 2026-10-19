package dev.hermes.android.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Status of the special permissions the agent needs and the Settings screens where the user grants them.
 * None of these can be granted by the app itself, so each has an Intent to the right screen.
 */
object DevicePermissions {

    fun notificationsGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True while the accessibility service is connected (enabled in Settings). */
    fun accessibilityConnected(): Boolean = AgentAccessibilityService.instance != null

    /** All-files access. Older Android versions have no such switch, so they count as granted. */
    fun allFilesGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun batteryUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    fun accessibilitySettings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun allFilesSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context))
        } else {
            appDetails(context)
        }

    fun batterySettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** App details: where the overflow menu offers "Allow restricted settings" for sideloaded apps. */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
}
