package com.jarvis.one

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.provider.ContactsContract
import androidx.core.app.ActivityCompat
import org.json.JSONObject

class ActionConfirmationActivity : Activity() {
    private lateinit var action: JSONObject
    private var pendingPhone: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        action = JSONObject(intent.getStringExtra("action") ?: "{}")
        AlertDialog.Builder(this)
            .setTitle(if (action.optString("type") == "call") "Confirm call" else "Confirm SMS")
            .setMessage(if (action.optString("type") == "call") "Call ${action.optString("name")}?"
                else "Send this SMS to ${action.optString("name")}?\n\n${action.optString("text")}")
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Confirm") { _, _ -> performAction() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun performAction() {
        val name = action.optString("name")
        val phone = findPhone(name)
        if (phone == null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(this, "No phone number found for $name.", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        if (action.optString("type") == "call") {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
                finish()
            } else {
                pendingPhone = phone
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 21)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                sendSms(phone)
                finish()
            } else {
                pendingPhone = phone
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 22)
            }
        }
    }

    private fun findPhone(name: String): String? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 20)
            return null
        }
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        return cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "Permission denied.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (requestCode == 20) {
            performAction()
        } else if (requestCode == 21) {
            pendingPhone?.let { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$it"))) }
            finish()
        } else if (requestCode == 22) {
            pendingPhone?.let { sendSms(it) }
            finish()
        }
    }

    private fun sendSms(phone: String) {
        val body = action.optString("text").trim()
        if (body.isBlank()) {
            android.widget.Toast.makeText(this, "The SMS message is empty.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            else smsManager.sendTextMessage(phone, null, body, null, null)
            android.widget.Toast.makeText(this, "SMS sent.", android.widget.Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            android.widget.Toast.makeText(this, error.message ?: "SMS could not be sent.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
