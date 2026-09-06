package com.morselink.feature.receive

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/**
 * CameraX image analysis + ZXing decoding.
 * ZXing is used instead of ML Kit on purpose: it is a pure-Java decoder with no
 * Play services dependency, so QR scanning also works on non-GMS devices (§3).
 */
class QrScanner(
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(com.google.zxing.BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        setHints(hints)
    }

    @Volatile
    var enabled = true

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        if (!enabled) {
            image.close()
            return
        }
        try {
            val plane = image.planes.firstOrNull()
            val buffer = plane?.buffer
            if (buffer == null) {
                image.close()
                return
            }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val source = PlanarYUVLuminanceSource(
                bytes,
                image.width,
                image.height,
                image.width / 6,
                image.height / 3,
                image.width * 2 / 3,
                image.height / 3,
                false,
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            result?.text?.let { text ->
                enabled = false
                onResult(text)
            }
        } catch (ignored: Exception) {
            // no code in this frame
        } catch (error: Throwable) {
            Log.w("QrScanner", "decode failed", error)
        } finally {
            image.close()
        }
    }
}
