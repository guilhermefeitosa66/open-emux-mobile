package com.openemux.mobile.share

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The QR code of [text]: error correction M, a quiet zone of 4 modules, [sizePx] square. Pure Java. */
fun qrMatrix(text: String, sizePx: Int): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, sizePx, sizePx,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
)

/** [qrMatrix] as black on white, ready for an `Image`. */
fun qrBitmap(text: String, sizePx: Int): ImageBitmap {
    val matrix = qrMatrix(text, sizePx)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
