package com.example.lockmodule

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.IOException

object NfcHelpers {
    fun processTag(tag: Tag, userHashKey: String?, callback: (String) -> Unit) {
        val result = if(!userHashKey.isNullOrEmpty()) "OK" else "NAK"
        callback(result)
    }

    fun fastRead(nfcA: NfcA, startPage: Byte, endPage: Byte): ByteArray? {
        val cmd = byteArrayOf(0x3A.toByte(), startPage, endPage)
        return try {
            nfcA.transceive(cmd)
        } catch (e: IOException) {
            null
        }
    }

    fun fastWrite(nfcA: NfcA, page: Byte, data: ByteArray): Boolean {
        if (data.size != 4) return false
        val cmd = byteArrayOf(0xA2.toByte(), page) + data
        return try {
            nfcA.transceive(cmd)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun verifyHashKey(nfcA: NfcA, hashKey: ByteArray, sramStartPage: Byte = 0xF8.toByte()): Boolean {
        for (i in 0 until 4) {
            val pageData = hashKey.sliceArray(i*4 until (i+1)*4)
            val success = fastWrite(nfcA, (sramStartPage + i).toByte(), pageData)
            if (!success) return false
        }
        val readBack = fastRead(nfcA, sramStartPage, (sramStartPage + 3).toByte()) ?: return false
        return readBack.contentEquals(hashKey)
    }

    fun buzzPhone(context: Context, durationMs: Long = 300) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}
