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

/** Linear barcodes need about ten modules of blank space at each end. */
private const val LINEAR_QUIET_ZONE = 10

/** Bars about a quarter as tall as the code is wide, and never shorter than this many modules. */
private const val LINEAR_ASPECT = 4
private const val LINEAR_MIN_HEIGHT = 24
private const val LINEAR_VERTICAL_MARGIN = 2

object CodeGenerator {

    fun generate(text: String, type: CodeType): Generated {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Generated.TooLong(emptyList())
        encode(text, type.format)?.let { return Generated.Code(render(it, type.quietZone)) }
        return Generated.TooLong(CodeType.entries.filter { it != type && encode(text, it.format) != null })
    }

    /**
     * Draws a read code again from its [text] so it can be scanned from the screen, upscaled for
     * display. [format] is the reader's name for the original symbology; when FerryQR cannot write
     * that one, or the content does not fit it, a QR code carries the same content instead.
     */
    fun redraw(text: String, format: String): Bitmap? {
        val original = writable(format)
        original?.let { barcode ->
            encode(text, barcode)?.let { return forExport(render(it, quietZoneOf(barcode), linear = it.height == 1)) }
        }
        val qr = encode(text, BarcodeFormat.QR_CODE) ?: return null
        return forExport(render(qr, CodeType.QR.quietZone))
    }

    /** Integer upscaling keeps module edges sharp. */
    fun forExport(code: Bitmap, target: Int = 1024): Bitmap {
        val factor = max(1, target / max(code.width, code.height))
        return Bitmap.createScaledBitmap(code, code.width * factor, code.height * factor, false)
    }

    /** Matrix codes the generator offers keep their quiet zone; linear codes need a wider one. */
    private fun quietZoneOf(format: BarcodeFormat): Int =
        CodeType.entries.find { it.format == format }?.quietZone ?: LINEAR_QUIET_ZONE

    /** zxing-cpp's format names mapped to the symbologies zxing can write. */
    private fun writable(name: String): BarcodeFormat? = when (name) {
        "QR_CODE" -> BarcodeFormat.QR_CODE
        "AZTEC" -> BarcodeFormat.AZTEC
        "DATA_MATRIX" -> BarcodeFormat.DATA_MATRIX
        "PDF_417" -> BarcodeFormat.PDF_417
        "CODE_128" -> BarcodeFormat.CODE_128
        "CODE_39", "CODE_39_STD", "CODE_39_EXT" -> BarcodeFormat.CODE_39
        "CODE_93" -> BarcodeFormat.CODE_93
        "CODABAR" -> BarcodeFormat.CODABAR
        "EAN_13" -> BarcodeFormat.EAN_13
        "EAN_8" -> BarcodeFormat.EAN_8
        "UPC_A" -> BarcodeFormat.UPC_A
        "UPC_E" -> BarcodeFormat.UPC_E
        "ITF", "ITF_14" -> BarcodeFormat.ITF
        else -> null
    }

    private fun encode(text: String, format: BarcodeFormat): BitMatrix? {
        val hints = buildMap<EncodeHintType, Any> {
            put(EncodeHintType.MARGIN, 0)
            // Plain Latin-1 text stays without an ECI header, which older readers handle best.
            if (!Charsets.ISO_8859_1.newEncoder().canEncode(text)) put(EncodeHintType.CHARACTER_SET, "UTF-8")
            if (format == BarcodeFormat.QR_CODE) put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        }
        return try {
            MultiFormatWriter().encode(text, format, 0, 0, hints)
        } catch (e: WriterException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * One pixel per module. A [linear] code comes as a single row, which is repeated to give the
     * bars a readable height, and its quiet zone is needed only at the sides.
     */
    private fun render(matrix: BitMatrix, quietZone: Int, linear: Boolean = false): Bitmap {
        val rows = if (linear) max(LINEAR_MIN_HEIGHT, matrix.width / LINEAR_ASPECT) else matrix.height
        val margin = if (linear) LINEAR_VERTICAL_MARGIN else quietZone
        val width = matrix.width + 2 * quietZone
        val height = rows + 2 * margin
        val pixels = IntArray(width * height) { Color.WHITE }
        for (y in 0 until rows) {
            val source = if (linear) 0 else y
            for (x in 0 until matrix.width) {
                if (matrix[x, source]) pixels[(y + margin) * width + x + quietZone] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
