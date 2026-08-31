package cc.kousen.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.os.BatteryManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import kotlin.math.roundToInt

class KioskPolicyManager(private val context: Context) {
    private val devicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)
    private var fullPolicyAppliedInProcess = false

    val adminComponent: ComponentName =
        ComponentName(context, KioskDeviceAdminReceiver::class.java)
    private val mainActivityComponent: ComponentName =
        ComponentName(context, MainActivity::class.java)

    fun isDeviceAdmin(): Boolean = devicePolicyManager.isAdminActive(adminComponent)

    fun isDeviceOwner(): Boolean =
        devicePolicyManager.isDeviceOwnerApp(context.packageName)

    fun isLockTaskPermitted(): Boolean =
        devicePolicyManager.isLockTaskPermitted(context.packageName)

    fun isInLockTaskMode(): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    }

    fun applyDeviceOwnerKioskPolicies() {
        if (!isDeviceOwner()) {
            Log.i(TAG, "Skipping Device Owner policies; app is not Device Owner")
            return
        }
        if (fullPolicyAppliedInProcess) {
            Log.d(TAG, "Skipping Device Owner policies; already applied in this process")
            return
        }

        devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
        Log.i(TAG, "Allowlisted ${context.packageName} for Lock Task Mode")
        runCatching {
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            devicePolicyManager.addPersistentPreferredActivity(
                adminComponent,
                homeFilter,
                mainActivityComponent,
            )
            Log.i(TAG, "Set Kousen Kiosk as persistent preferred Home activity")
        }.onFailure { error ->
            Log.w(TAG, "Unable to set persistent preferred Home activity", error)
        }
        devicePolicyManager.setLockTaskFeatures(
            adminComponent,
            DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
        )
        Log.i(TAG, "Set Lock Task features to LOCK_TASK_FEATURE_NONE")
        runCatching {
            val disabled = devicePolicyManager.setKeyguardDisabled(adminComponent, true)
            Log.i(TAG, "Requested keyguard disabled: $disabled")
        }.onFailure { error ->
            Log.w(TAG, "Unable to disable keyguard", error)
        }
        runCatching {
            devicePolicyManager.setStatusBarDisabled(adminComponent, true)
            Log.i(TAG, "Requested status bar disabled outside Lock Task Mode")
        }.onFailure { error ->
            Log.w(TAG, "Unable to disable status bar", error)
        }
        runCatching {
            devicePolicyManager.setSystemSetting(
                adminComponent,
                Settings.System.SCREEN_OFF_TIMEOUT,
                DEFAULT_SCREEN_OFF_TIMEOUT_MS.toString(),
            )
            Log.i(TAG, "Set screen-off timeout to $DEFAULT_SCREEN_OFF_TIMEOUT_MS ms")
        }.onFailure { error ->
            Log.w(TAG, "Unable to set screen-off timeout", error)
        }
        runCatching {
            val pluggedInModes = BatteryManager.BATTERY_PLUGGED_AC or
                BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            devicePolicyManager.setGlobalSetting(
                adminComponent,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                pluggedInModes.toString(),
            )
            Log.i(TAG, "Set stay-awake-while-plugged-in modes to $pluggedInModes")
        }.onFailure { error ->
            Log.w(TAG, "Unable to set stay-awake-while-plugged-in", error)
        }
        applySystemUpdatePolicy()
        applyUserRestrictions()
        hideKnownConsumerApps()
        fullPolicyAppliedInProcess = true
    }

    fun setScreenBrightness(percent: Int): Boolean {
        val clampedPercent = percent.coerceIn(1, 100)
        val brightness = (clampedPercent / 100f * MAX_SCREEN_BRIGHTNESS).roundToInt()
            .coerceIn(1, MAX_SCREEN_BRIGHTNESS)

        return runCatching {
            if (isDeviceOwner()) {
                devicePolicyManager.setSystemSetting(
                    adminComponent,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString(),
                )
                devicePolicyManager.setSystemSetting(
                    adminComponent,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness.toString(),
                )
            } else {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness,
                )
            }
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to set screen brightness", error)
        }.getOrDefault(false)
    }

    fun temporarilyRelaxForAdminSettings() {
        if (!isDeviceOwner()) return

        runCatching {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            devicePolicyManager.setStatusBarDisabled(adminComponent, false)
            fullPolicyAppliedInProcess = false
            Log.i(TAG, "Temporarily relaxed kiosk policies for admin settings")
        }.onFailure { error ->
            Log.w(TAG, "Unable to relax kiosk policies for admin settings", error)
        }
    }

    fun reapplyFullPolicyOnNextResume() {
        fullPolicyAppliedInProcess = false
    }

    private fun applySystemUpdatePolicy() {
        runCatching {
            val policy = SystemUpdatePolicy.createWindowedInstallPolicy(
                SYSTEM_UPDATE_WINDOW_START_MINUTES,
                SYSTEM_UPDATE_WINDOW_END_MINUTES,
            )
            devicePolicyManager.setSystemUpdatePolicy(adminComponent, policy)
            Log.i(
                TAG,
                "Set system update policy window to " +
                    "$SYSTEM_UPDATE_WINDOW_START_MINUTES-$SYSTEM_UPDATE_WINDOW_END_MINUTES",
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to set system update policy", error)
        }
    }

    private fun applyUserRestrictions() {
        KIOSK_USER_RESTRICTIONS.forEach { restriction ->
            runCatching {
                devicePolicyManager.addUserRestriction(adminComponent, restriction)
                Log.i(TAG, "Applied user restriction $restriction")
            }.onFailure { error ->
                Log.w(TAG, "Unable to apply restriction $restriction", error)
            }
        }
    }

    private fun hideKnownConsumerApps() {
        KNOWN_CONSUMER_PACKAGES
            .filter(::isPackageInstalled)
            .forEach { packageName ->
                runCatching {
                    val hidden = devicePolicyManager.setApplicationHidden(
                        adminComponent,
                        packageName,
                        true,
                    )
                    Log.i(TAG, "Requested hidden=$hidden for $packageName")
                }.onFailure { error ->
                    Log.w(TAG, "Unable to hide $packageName", error)
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            true
        }.getOrDefault(false)

    fun startLockTaskIfPermitted(activity: Activity) {
        if (!isLockTaskPermitted()) {
            Log.w(TAG, "Lock Task Mode is not permitted for ${context.packageName}")
            return
        }
        if (isInLockTaskMode()) return
        runCatching {
            activity.startLockTask()
            Log.i(TAG, "Requested Lock Task Mode start")
        }.onFailure { error ->
            Log.w(TAG, "Unable to start lock task mode", error)
        }
    }

    companion object {
        private const val TAG = "KioskPolicyManager"
        private const val DEFAULT_SCREEN_OFF_TIMEOUT_MS = 30 * 60 * 1000
        private const val MAX_SCREEN_BRIGHTNESS = 255
        private const val SYSTEM_UPDATE_WINDOW_START_MINUTES = 3 * 60
        private const val SYSTEM_UPDATE_WINDOW_END_MINUTES = 4 * 60

        private val KIOSK_USER_RESTRICTIONS = listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_CONFIG_BLUETOOTH,
            UserManager.DISALLOW_CONFIG_CREDENTIALS,
            UserManager.DISALLOW_CONFIG_LOCATION,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_CREATE_WINDOWS,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
        )

        private val KNOWN_CONSUMER_PACKAGES = listOf(
            "com.android.otaprovisioningclient",
            "com.android.vending",
            "com.google.android.apps.docs",
            "com.google.android.apps.maps",
            "com.google.android.apps.messaging",
            "com.google.android.apps.photos",
            "com.google.android.apps.youtube.kids",
            "com.google.android.calendar",
            "com.google.android.contacts",
            "com.google.android.feedback",
            "com.google.android.gm",
            "com.google.android.googlequicksearchbox",
            "com.google.android.setupwizard",
            "com.google.android.youtube",
            "com.tcl.android.launcher.hotapp",
            "com.tcl.camera",
            "com.tcl.demopage",
            "com.tcl.favorites",
            "com.tcl.fmradio",
            "com.tcl.fota.system",
            "com.tcl.screenrecorder",
            "com.tcl.userguide",
            "com.tct.gdpr",
            "com.tct.onetouchbooster",
            "com.tct.retaildemo",
            "com.tct.setupwizard",
            "com.tct.smart.aota",
            "com.tct.smart.nps",
            "com.tct.smart.sidebar",
            "com.tct.smart.switchphone",
        )
    }
}
