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

class KioskPolicyManager(private val context: Context) {
    private val devicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

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
            "com.google.android.youtube",
        )
    }
}
