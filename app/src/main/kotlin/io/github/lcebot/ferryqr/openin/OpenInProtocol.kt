package io.github.lcebot.ferryqr.openin

/**
 * Names shared by FerryQR and its optional LSPosed half in other apps. Both sides compile from this
 * file, so an action or extra can never be spelled differently on one end.
 *
 * Every broadcast names its target package, which alone keeps one app from receiving another's
 * requests. Every broadcast is also ordered and answered through its result code, because the
 * target app cannot see FerryQR under package visibility and so could not send one of its own back.
 */
internal object OpenInProtocol {

    /** Declared by FerryQR with signature protection; the receivers require it of the sender. */
    const val PERMISSION = "io.github.lcebot.ferryqr.permission.OPEN_IN"

    /** Answered by the app's main process, which then opens [EXTRA_TEXT] or [EXTRA_IMAGE]. */
    const val ACTION_HANDOFF = "io.github.lcebot.ferryqr.action.HANDOFF"

    const val EXTRA_TEXT = "text"

    /**
     * JPEG bytes, kept to a few tens of kilobytes so retried broadcasts stay far below binder limits.
     * Alone, it is a code FerryQR could not read. Next to [EXTRA_TEXT], it is that code drawn again,
     * shown to the camera where the text cannot be returned as a result, since the module draws
     * text only as a QR code.
     */
    const val EXTRA_IMAGE = "image"

    /**
     * Identifies one handoff across its retries, so a late retry cannot restart it. It is the
     * elapsedRealtimeNanos at which the handoff began, just after its scanner was launched, so a
     * scanner screen created before it was opened by the user and not by FerryQR.
     */
    const val EXTRA_ID = "id"

    /** A key of [Strategy.ReplaceResult.types], for apps whose result names the kind of code. */
    const val EXTRA_TYPE = "type"

    /** Asks an app whose result is replaced to show the text to its camera instead. */
    const val EXTRA_CAMERA_FEED = "camera_feed"

    /** A receiver took the request. */
    const val RESULT_ACCEPTED = -1

    /** A receiver saw the request but cannot serve it, so retrying is pointless. */
    const val RESULT_DECLINED = 1

    /** The initial code, left unchanged when no receiver is registered. */
    const val RESULT_UNANSWERED = 0
}
