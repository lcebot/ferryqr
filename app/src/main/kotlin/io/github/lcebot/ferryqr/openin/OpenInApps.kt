package io.github.lcebot.ferryqr.openin

/** How content reaches one app's scanner. */
internal sealed interface Strategy {

    /**
     * Any of [activities], the app's camera screens, closes as soon as it is created and returns
     * [extras] to the screen that opened it, as if it had read a code. Only screens opened for a
     * result by one of [callers] are closed, so a camera the app opens for anything else is left
     * alone. [TEXT] in a value stands for the content. Pictures cannot be returned this way and are
     * declined.
     */
    data class FinishWithResult(
        val activities: Set<String>,
        val callers: Set<String>,
        val extras: Map<String, String>,
    ) : Strategy

    /**
     * The scanner's result object is built with the content in place of what its decoder read. A
     * small placeholder code is shown to the camera so the decoder builds one at all. The argument
     * fields are indices into [params], the constructor's parameter types. Pictures, and text when
     * the camera feed is asked for, are shown to the camera as they are.
     */
    data class ReplaceResult(
        val className: String,
        val params: List<String>,
        val textArg: Int,
        val dataArg: Int,
        val charsetArg: Int,
        val typeIdArg: Int,
        val typeNameArg: Int,
        val types: Map<String, TypeMapping>,
    ) : Strategy

    /**
     * The scanner's camera is shown a QR code of the content, or the picture. It relies on framework
     * camera APIs only, so it suits apps whose decoder classes are renamed with every version.
     */
    data object FeedCamera : Strategy

    companion object {
        const val TEXT = "\$text"
    }
}

/** How the app's decoder names one kind of code, by number and by name. */
internal data class TypeMapping(val id: Int, val name: String)

internal data class OpenInApp(val pkg: String, val strategy: Strategy)

/**
 * The apps the LSPosed helper supports. Each one also needs its line in META-INF/xposed/scope.list
 * and its package query in the manifest, which OpenInAppsTest checks.
 */
internal object OpenInApps {
    const val WECHAT = "com.tencent.mm"
    const val DIDI = "com.sdu.didi.psnger"
    const val MEITUAN = "com.sankuai.meituan"

    /** The key in [Strategy.ReplaceResult.types] used when a handoff names no type. */
    const val DEFAULT_TYPE = "qr"

    /** The key for WeChat mini program codes, whose content means nothing as a QR code. */
    const val MINI_PROGRAM_TYPE = "wxcode"

    val all = listOf(
        // The QBar SDK keeps its names across WeChat versions; the rest of WeChat is obfuscated.
        OpenInApp(
            WECHAT,
            Strategy.ReplaceResult(
                className = "com.tencent.qbar.WxQBarResult",
                params = listOf("int", "String", "String", "byte[]", "String", "int"),
                typeIdArg = 0,
                typeNameArg = 1,
                textArg = 2,
                dataArg = 3,
                charsetArg = 4,
                types = mapOf(
                    "qr" to TypeMapping(19, "QR_CODE"),
                    MINI_PROGRAM_TYPE to TypeMapping(22, "WX_CODE"),
                ),
            ),
        ),
        // Didi's scanner works out which business a code belongs to before returning it, and its
        // decoder classes are obfuscated, so only its camera can be handed the code.
        OpenInApp(DIDI, Strategy.FeedCamera),
        // ScanQRInMeituanActivity opens one of these camera screens for a result and handles the
        // code itself once it returns, the same way as after a real scan.
        OpenInApp(
            MEITUAN,
            Strategy.FinishWithResult(
                activities = setOf(
                    "com.meituan.android.scan.ArSupportCaptureActivity",
                    "com.meituan.android.scan.NewArActivity",
                ),
                callers = setOf("com.meituan.android.scan.ScanQRInMeituanActivity"),
                extras = mapOf(
                    "result_type" to "qr",
                    "result_url" to Strategy.TEXT,
                    "barcode_format" to "QR_CODE",
                ),
            ),
        ),
    )

    private val byPackage = all.associateBy { it.pkg }

    fun find(pkg: String): OpenInApp? = byPackage[pkg]
}
