package com.openemux.mobile.share

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrTest {
    @Test
    fun `the code reads back as the address it encodes`() {
        val uri = ShareAddress("192.168.1.20", 53319, "ab".repeat(32)).format()
        val matrix = qrMatrix(uri, 300)
        assertEquals(300, matrix.width)
        assertEquals(300, matrix.height)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        assertEquals(uri, QRCodeReader().decode(bitmap).text)
    }
}
