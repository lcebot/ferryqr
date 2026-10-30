package io.github.lcebot.ferryqr.hook

import io.github.lcebot.ferryqr.helper.HelperProtocol
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** Listed in META-INF/xposed/java_init.list; created by the framework once per WeChat process. */
class HookEntry : XposedModule() {
    @Volatile
    private var started = false

    @Volatile
    private var processName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookLog.attach(this)
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != HelperProtocol.WECHAT) return
        synchronized(this) {
            if (started) return
            started = true
        }
        WeChatHooks.install(this, processName == HelperProtocol.WECHAT)
    }
}
