package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import io.github.lcebot.ferryqr.scan.FrozenFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Whether a layer can blur what it draws, which takes a RenderEffect and so API 31. */
internal val LAYERS_BLUR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Where layers cannot blur, the frozen picture fades into a copy of itself a sixteenth of each side,
 * which filtering spreads back over the picture as a soft blur. Four halvings make that copy, since
 * each one averages the pixels it merges, where a single step this far down would skip most of them.
 */
private const val SOFT_STILL_HALVINGS = 4

/**
 * How long a reset waits for a frame to freeze on. The analysis thread hands it over once it is
 * done with the frame before, and a camera that sends none in this time has no picture worth keeping.
 */
private const val STILL_WAIT_MS = 500L

/**
 * Passes each frame on to [analyzer], first making a bitmap of it when one is asked for. The
 * viewfinder's SurfaceView shows the camera on a child surface, which PixelCopy cannot read through
 * the view, so the still a reset freezes on comes from the analysis stream, whose field of view the
 * preview shares as both use the same resolution selector.
 */
internal class StillTaker(private val analyzer: ImageAnalysis.Analyzer) : ImageAnalysis.Analyzer {
    @Volatile
    private var wanted: CompletableDeferred<Shot?>? = null

    /**
     * The next frame in the camera's own orientation, or null when the camera sends none in time
     * or the frame cannot be converted.
     */
    suspend fun take(): Shot? {
        val still = CompletableDeferred<Shot?>()
        wanted = still
        try {
            return withTimeoutOrNull(STILL_WAIT_MS) { still.await() }
        } finally {
            if (wanted === still) wanted = null
            // A still made once the wait is over finds the request cancelled and is released at once.
            still.cancel()
        }
    }

    override fun analyze(image: ImageProxy) {
        wanted?.let { still ->
            wanted = null
            val shot = try {
                Shot(FrozenFrame(image.toBitmap()))
            } catch (e: RuntimeException) {
                null
            }
            if (!still.complete(shot)) shot?.release()
        }
        analyzer.analyze(image)
    }

    override fun getDefaultTargetResolution(): Size? = analyzer.defaultTargetResolution

    override fun getTargetCoordinateSystem(): Int = analyzer.targetCoordinateSystem

    override fun updateTransform(matrix: android.graphics.Matrix?) = analyzer.updateTransform(matrix)
}

/**
 * A frame taken for a still, released with it once the still has left the screen. Where layers
 * cannot blur, [soft] is the small copy that stands in for the blur, made with the frame on the
 * analysis thread so the reset's first frames on the main thread are not held up by it.
 */
internal class Shot(val frame: FrozenFrame) {
    val soft: Bitmap? = if (LAYERS_BLUR) null else frame.bitmap.softened()

    fun release() {
        frame.release()
        soft?.recycle()
    }
}

/** A copy [SOFT_STILL_HALVINGS] times half the size, each step filtered. */
private fun Bitmap.softened(): Bitmap {
    var copy = this
    repeat(SOFT_STILL_HALVINGS) {
        val next = Bitmap.createScaledBitmap(
            copy,
            (copy.width / 2).coerceAtLeast(1),
            (copy.height / 2).coerceAtLeast(1),
            true,
        )
        if (copy !== this && next !== copy) copy.recycle()
        copy = next
    }
    return copy
}

/**
 * A still of the camera's picture in the camera's own orientation, taken while the viewfinder's
 * [transformer] placed the picture of [shown]. The image, and its [soft] copy where there is one,
 * are stretched over the surface's [resolution], which [toViewfinder] places in the viewfinder as the
 * picture was placed when the still was taken, so the still lies exactly over the picture it replaces.
 */
internal class Still(val shot: Shot, shown: SurfaceRequest, transformer: MutableCoordinateTransformer) {
    val resolution = IntSize(shown.resolution.width, shown.resolution.height)
    val toViewfinder = Matrix().apply {
        setFrom(transformer.transformMatrix)
        invert()
    }
    val image = shot.frame.bitmap.asImageBitmap()
    val soft = shot.soft?.asImageBitmap()
}
