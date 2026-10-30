package io.github.lcebot.ferryqr.generate

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.StringRes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.lcebot.ferryqr.R
import kotlin.math.max

/** [quietZone] is the blank margin, in modules, that readers need around the symbol. */
enum class CodeType(@param:StringRes val label: Int, val format: BarcodeFormat, val quietZone: Int) {
    QR(R.string.type_qr, BarcodeFormat.QR_CODE, 4),
    AZTEC(R.string.type_aztec, BarcodeFormat.AZTEC, 2),
    DATA_MATRIX(R.string.type_data_matrix, BarcodeFormat.DATA_MATRIX, 2),
    PDF417(R.string.type_pdf417, BarcodeFormat.PDF_417, 2),
}

sealed interface Generated {
    /** One pixel per module; scale it up without smoothing for display or export. */
    data class Code(val bitmap: Bitmap) : Generated

    /** The text does not fit the chosen type; [alternatives] are the types that can hold it. */
    data class TooLong(val alternatives: List<CodeType>) : Generated
}

/** Beyond this many UTF-8 bytes no supported type can hold the text, so encoding is not attempted. */
private const val MAX_BYTES = 3200

object CodeGenerator {

    fun generate(text: String, type: CodeType): Generated {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Generated.TooLong(emptyList())
        encode(text, type)?.let { return Generated.Code(render(it, type.quietZone)) }
        return Generated.TooLong(CodeType.entries.filter { it != type && encode(text, it) != null })
    }

    /** Integer upscaling keeps module edges sharp. */
    fun forExport(code: Bitmap, target: Int = 1024): Bitmap {
        val factor = max(1, target / max(code.width, code.height))
        return Bitmap.createScaledBitmap(code, code.width * factor, code.height * factor, false)
    }

    private fun encode(text: String, type: CodeType): BitMatrix? {
        val hints = buildMap<EncodeHintType, Any> {
            put(EncodeHintType.MARGIN, 0)
            // Plain Latin-1 text stays without an ECI header, which older readers handle best.
            if (!Charsets.ISO_8859_1.newEncoder().canEncode(text)) put(EncodeHintType.CHARACTER_SET, "UTF-8")
            if (type == CodeType.QR) put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        }
        return try {
            MultiFormatWriter().encode(text, type.format, 0, 0, hints)
        } catch (e: WriterException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun render(matrix: BitMatrix, quietZone: Int): Bitmap {
        val width = matrix.width + 2 * quietZone
        val height = matrix.height + 2 * quietZone
        val pixels = IntArray(width * height) { Color.WHITE }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) pixels[(y + quietZone) * width + x + quietZone] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
