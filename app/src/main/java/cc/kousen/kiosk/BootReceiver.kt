package cc.kousen.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val policyManager = KioskPolicyManager(context)
        val shouldLaunch = policyManager.isDeviceOwner() &&
            (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)

        if (!shouldLaunch) {
            Log.i(TAG, "Skipping boot launch for action=${intent.action}")
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "KioskBootReceiver"
    }
}
