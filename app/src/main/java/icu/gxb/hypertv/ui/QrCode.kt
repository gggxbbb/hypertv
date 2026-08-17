package icu.gxb.hypertv.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 用 ZXing 生成内容为 [content] 的二维码 Bitmap。
 * 纯 CPU 操作，应在后台线程调用。
 * 生成失败（如内容过长）返回 null。
 */
fun generateQrCode(content: String, size: Int = 512, quietZone: Int = 1): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quietZone,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            .apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    } catch (_: Exception) {
        null
    }
}
