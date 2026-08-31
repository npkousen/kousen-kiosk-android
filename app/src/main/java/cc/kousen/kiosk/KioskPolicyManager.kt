package cc.kousen.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

class KioskPolicyManager(private val context: Context) {
    private val devicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    val adminComponent: ComponentName =
        ComponentName(context, KioskDeviceAdminReceiver::class.java)

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
        devicePolicyManager.setLockTaskFeatures(
            adminComponent,
            DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
        )
        Log.i(TAG, "Set Lock Task features to LOCK_TASK_FEATURE_NONE")
        listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_SAFE_BOOT,
        ).forEach { restriction ->
            runCatching {
                devicePolicyManager.addUserRestriction(adminComponent, restriction)
            }.onFailure { error ->
                Log.w(TAG, "Unable to apply restriction $restriction", error)
            }
        }
    }

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
    }
}
