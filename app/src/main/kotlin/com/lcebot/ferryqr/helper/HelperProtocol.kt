package com.lcebot.ferryqr.helper

/**
 * Names shared by FerryQR and its optional LSPosed half in WeChat. Both sides compile from this
 * file, so an action or extra can never be spelled differently on one end.
 *
 * Every broadcast is ordered and answered through its result code, because WeChat cannot see
 * FerryQR under package visibility and so could not send a broadcast of its own back.
 */
internal object HelperProtocol {
    const val WECHAT = "com.tencent.mm"

    /** Declared by FerryQR with signature protection; WeChat's receivers require it of the sender. */
    const val PERMISSION = "com.lcebot.ferryqr.permission.WECHAT_HELPER"

    /** Answered by every WeChat process the module is loaded in. */
    const val ACTION_PROBE = "com.lcebot.ferryqr.action.PROBE"

    /**
     * Answered by WeChat's main process, which then feeds the next camera frames either a QR code
     * of [EXTRA_TEXT] or the picture in [EXTRA_IMAGE], for codes FerryQR located but cannot read.
     */
    const val ACTION_HANDOFF = "com.lcebot.ferryqr.action.HANDOFF"
    const val EXTRA_TEXT = "text"

    /** JPEG bytes, kept to a few tens of kilobytes so retried broadcasts stay far below binder limits. */
    const val EXTRA_IMAGE = "image"

    /** Identifies one handoff across its retries, so a late retry cannot restart the feed. */
    const val EXTRA_ID = "id"

    /** A receiver took the request. */
    const val RESULT_ACCEPTED = -1

    /** A receiver saw the request but cannot serve it, so retrying is pointless. */
    const val RESULT_DECLINED = 1

    /** The initial code, left unchanged when no receiver is registered. */
    const val RESULT_UNANSWERED = 0
}
