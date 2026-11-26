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
import kotlin.math.roundToInt

/** How the format picker groups formats: 2D codes, retail product barcodes, and other barcodes. */
enum class CodeFamily(@param:StringRes val label: Int) {
    MATRIX(R.string.format_group_matrix),
    PRODUCT(R.string.format_group_product),
    LINEAR(R.string.format_group_linear),
}

/**
 * Every format FerryQR can draw, in the order the format picker lists them. [note] says what the
 * format is for. [quietZone] is the blank margin, in modules, that readers need around the symbol.
 */
enum class CodeType(
    @param:StringRes val label: Int,
    @param:StringRes val note: Int,
    val format: BarcodeFormat,
    val family: CodeFamily,
    val quietZone: Int,
) {
    QR(R.string.type_qr, R.string.format_note_qr, BarcodeFormat.QR_CODE, CodeFamily.MATRIX, 4),
    DATA_MATRIX(R.string.type_data_matrix, R.string.format_note_data_matrix, BarcodeFormat.DATA_MATRIX, CodeFamily.MATRIX, 2),
    AZTEC(R.string.type_aztec, R.string.format_note_aztec, BarcodeFormat.AZTEC, CodeFamily.MATRIX, 2),
    PDF417(R.string.type_pdf417, R.string.format_note_pdf417, BarcodeFormat.PDF_417, CodeFamily.MATRIX, 2),
    EAN_13(R.string.type_ean_13, R.string.format_note_ean_13, BarcodeFormat.EAN_13, CodeFamily.PRODUCT, LINEAR_QUIET_ZONE),
    EAN_8(R.string.type_ean_8, R.string.format_note_ean_8, BarcodeFormat.EAN_8, CodeFamily.PRODUCT, LINEAR_QUIET_ZONE),
    UPC_A(R.string.type_upc_a, R.string.format_note_upc_a, BarcodeFormat.UPC_A, CodeFamily.PRODUCT, LINEAR_QUIET_ZONE),
    UPC_E(R.string.type_upc_e, R.string.format_note_upc_e, BarcodeFormat.UPC_E, CodeFamily.PRODUCT, LINEAR_QUIET_ZONE),
    CODE_128(R.string.type_code_128, R.string.format_note_code_128, BarcodeFormat.CODE_128, CodeFamily.LINEAR, LINEAR_QUIET_ZONE),
    CODE_39(R.string.type_code_39, R.string.format_note_code_39, BarcodeFormat.CODE_39, CodeFamily.LINEAR, LINEAR_QUIET_ZONE),
    CODE_93(R.string.type_code_93, R.string.format_note_code_93, BarcodeFormat.CODE_93, CodeFamily.LINEAR, LINEAR_QUIET_ZONE),
    CODABAR(R.string.type_codabar, R.string.format_note_codabar, BarcodeFormat.CODABAR, CodeFamily.LINEAR, LINEAR_QUIET_ZONE),
    ITF(R.string.type_itf, R.string.format_note_itf, BarcodeFormat.ITF, CodeFamily.LINEAR, LINEAR_QUIET_ZONE),
    ;

    /** A barcode of one row of bars, as opposed to a 2D code. */
    val linear: Boolean get() = family != CodeFamily.MATRIX

    /** Long and flat, so it is enlarged by tilting or stretching rather than by scaling alone. */
    val long: Boolean get() = linear || this == PDF417

    companion object {
        /** The formats the create page offers at once; the others are one tap away in the picker. */
        val COMMON = listOf(QR, DATA_MATRIX, AZTEC, PDF417)

        /** The format a reader named [name], as zxing-cpp names them, or null when FerryQR cannot draw it. */
        fun ofReaderName(name: String): CodeType? = when (name) {
            "CODE_39_STD", "CODE_39_EXT" -> CODE_39
            "ITF_14" -> ITF
            else -> entries.firstOrNull { it.format.name == name }
        }
    }
}

sealed interface Generated {
    /** One pixel per module; scale it up without smoothing for display or export. */
    data class Code(val bitmap: Bitmap) : Generated

    /** The text does not fit the chosen type, for [problem]; [alternatives] are common types that can hold it. */
    data class Unfit(val problem: CodeProblem, val alternatives: List<CodeType>) : Generated
}

/** Linear barcodes need about ten modules of blank space at each end. */
private const val LINEAR_QUIET_ZONE = 10

/** Bars about a quarter as tall as the code is wide, and never shorter than this many modules. */
private const val LINEAR_ASPECT = 4
private const val LINEAR_MIN_HEIGHT = 24
private const val LINEAR_VERTICAL_MARGIN = 2

object CodeGenerator {

    /**
     * [heightRatio], for a long code, is the height wanted for every unit of width, quiet zone
     * included: a barcode gets bars that tall and a PDF417 rows that tall, so a code drawn to fill
     * a taller area keeps every module sharp and in proportion. Without [quietZone] the code has no
     * margin at all, for a preview that gives every format the same margin of its own. Without
     * [withAlternatives], content that does not fit comes back with no alternatives, which saves
     * encoding it in every common format where they are not shown.
     */
    fun generate(
        text: String,
        type: CodeType,
        heightRatio: Float? = null,
        quietZone: Boolean = true,
        withAlternatives: Boolean = true,
    ): Generated {
        val problem = CodeRules.problem(text, type)
        val matrix = if (problem == null) encode(text, type.format) else null
        if (matrix != null) {
            val margin = if (quietZone) type.quietZone else 0
            return Generated.Code(render(matrix, margin, linear = type.linear, heightRatio = heightRatio.takeIf { type.long }))
        }
        val alternatives = if (!withAlternatives) {
            emptyList()
        } else {
            CodeType.COMMON.filter { it != type && CodeRules.problem(text, it) == null && encode(text, it.format) != null }
        }
        return Generated.Unfit(problem ?: CodeRules.unencodable(type), alternatives)
    }

    /** [text] drawn as [type], upscaled for display and export; null when it does not fit. */
    fun draw(text: String, type: CodeType, heightRatio: Float? = null): Bitmap? =
        (generate(text, type, heightRatio) as? Generated.Code)?.bitmap?.let(::forExport)

    /**
     * Draws a read code again from its [text] so it can be scanned from the screen, upscaled for
     * display. [format] is the reader's name for the original symbology; when FerryQR cannot write
     * that one, or the content does not fit it, a QR code carries the same content instead.
     */
    fun redraw(text: String, format: String): Bitmap? =
        CodeType.ofReaderName(format)?.let { draw(text, it) } ?: draw(text, CodeType.QR)

    /** Integer upscaling keeps module edges sharp. */
    fun forExport(code: Bitmap, target: Int = 1024): Bitmap {
        val factor = max(1, target / max(code.width, code.height))
        return Bitmap.createScaledBitmap(code, code.width * factor, code.height * factor, false)
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
     * bars a readable height, and its quiet zone is needed only at the sides, with a sliver above and
     * below unless [quietZone] is zero. With [heightRatio] the bars, or the rows of a stacked code,
     * are repeated until the image is about that much taller than it is wide.
     */
    private fun render(matrix: BitMatrix, quietZone: Int, linear: Boolean, heightRatio: Float? = null): Bitmap {
        val single = linear && matrix.height == 1
        val margin = if (linear) minOf(quietZone, LINEAR_VERTICAL_MARGIN) else quietZone
        val width = matrix.width + 2 * quietZone
        val wanted = heightRatio?.let { (width * it).roundToInt() - 2 * margin }
        val repeat = if (single || wanted == null) 1 else max(1, (wanted.toFloat() / matrix.height).roundToInt())
        val rows = if (single) max(LINEAR_MIN_HEIGHT, wanted ?: (matrix.width / LINEAR_ASPECT)) else matrix.height * repeat
        val height = rows + 2 * margin
        val pixels = IntArray(width * height) { Color.WHITE }
        for (y in 0 until rows) {
            val source = if (single) 0 else y / repeat
            for (x in 0 until matrix.width) {
                if (matrix[x, source]) pixels[(y + margin) * width + x + quietZone] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
