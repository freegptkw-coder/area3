package com.aria.assistant.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

object SmsTaskHandler {

    data class SmsPreview(
        val address: String,
        val body: String,
        val timestamp: Long
    )

    fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun latestIncoming(context: Context, limit: Int = 3): List<SmsPreview> {
        if (!hasReadSmsPermission(context)) return emptyList()

        val list = mutableListOf<SmsPreview>()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val iAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody = cursor.getColumnIndex(Telephony.Sms.BODY)
            val iDate = cursor.getColumnIndex(Telephony.Sms.DATE)

            while (cursor.moveToNext() && list.size < limit) {
                val address = if (iAddress >= 0) cursor.getString(iAddress).orEmpty() else "Unknown"
                val body = if (iBody >= 0) cursor.getString(iBody).orEmpty() else ""
                val date = if (iDate >= 0) cursor.getLong(iDate) else 0L
                list += SmsPreview(address = address, body = body, timestamp = date)
            }
        }

        return list
    }

    fun latestCompactSummary(context: Context): String {
        if (!hasReadSmsPermission(context)) return "READ_SMS permission missing"
        val latest = latestIncoming(context, limit = 1).firstOrNull() ?: return "No incoming SMS found"
        val body = latest.body.replace(Regex("\\s+"), " ").trim().take(140)
        return "Latest SMS from ${latest.address}: $body"
    }
}
