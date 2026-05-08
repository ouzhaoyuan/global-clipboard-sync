package com.clipboard.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_NOTIFICATION = 1002
        private const val REQUEST_ACCESSIBILITY = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkAllPermissions()) {
            startServicesAndFinish()
        } else {
            requestMissingPermissions()
        }
    }

    private fun checkAllPermissions(): Boolean {
        return hasOverlayPermission() &&
                hasNotificationPermission() &&
                isAccessibilityEnabled()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    /**
     * 使用 Settings.Secure 查询已启用的无障碍服务列表
     * 比 AccessibilityManager.getEnabledAccessibilityServiceList() 可靠得多
     * 后者在很多手机上明明开了也返回空列表
     */
    private fun isAccessibilityEnabled(): Boolean {
        val expectedService = "$packageName/${packageName}.ClipboardAccessibilityService"
        val enabledServices = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        }
        return enabledServices?.contains(expectedService) == true
    }

    private fun requestMissingPermissions() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "请授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        if (!hasNotificationPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
            }
            return
        }

        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请开启「无障碍服务」权限，找到 GlobalClipboardSync 并开启", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityForResult(intent, REQUEST_ACCESSIBILITY)
            return
        }

        startServicesAndFinish()
    }

    @Deprecated("Using for simplicity on older APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Re-check all permissions after returning from any settings page
        if (checkAllPermissions()) {
            startServicesAndFinish()
        } else {
            // Still missing some permission, continue requesting
            requestMissingPermissions()
        }
    }

    @Deprecated("Using for simplicity on older APIs")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkAllPermissions()) {
            startServicesAndFinish()
        } else {
            requestMissingPermissions()
        }
    }

    private fun startServicesAndFinish() {
        ClipboardSyncService.start(this)
        finish()
    }
}
