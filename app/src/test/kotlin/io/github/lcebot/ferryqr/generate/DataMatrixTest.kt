package io.github.lcebot.ferryqr.generate

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import kotlin.test.Test
import kotlin.test.assertEquals

/** Whether Data Matrix holds text beyond Latin-1 depends on which of zxing's encoders writes it. */
class DataMatrixTest {

    @Test
    fun holdsChineseTextThatReadsBackTheSame() {
        val matrix = CodeGenerator.encode("你好", BarcodeFormat.DATA_MATRIX)!!
        val width = (matrix.width + 2 * MARGIN) * SCALE
        val height = (matrix.height + 2 * MARGIN) * SCALE
        val pixels = IntArray(width * height) { index ->
            val x = index % width / SCALE - MARGIN
            val y = index / width / SCALE - MARGIN
            val dark = x in 0 until matrix.width && y in 0 until matrix.height && matrix[x, y]
            if (dark) BLACK else WHITE
        }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val read = DataMatrixReader().decode(image, mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals("你好", read.text)
    }

    private companion object {
        const val SCALE = 4
        const val MARGIN = 2
        val BLACK = 0xFF000000.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
