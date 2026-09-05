package com.morselink.core.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * QR encoding for the pairing screen (§14.8).
 *
 * Only `zxing:core` is on the classpath — not `zxing-android-embedded` — so the
 * BitMatrix is converted to a Bitmap here rather than via `BarcodeEncoder`.
 */
object QrCode {

    fun bitmap(contents: String, size: Int = 512): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
        matrix.toBitmap()
    }.getOrNull()

    private fun BitMatrix.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
