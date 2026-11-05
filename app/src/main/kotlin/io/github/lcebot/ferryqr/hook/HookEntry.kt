package io.github.lcebot.ferryqr.hook

import io.github.lcebot.ferryqr.openin.OpenInApps
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.ConcurrentHashMap

/**
 * Listed in META-INF/xposed/java_init.list; created by the framework once per process of every app
 * in scope.list. Which apps are supported, and how each is hooked, is listed in [OpenInApps].
 */
class HookEntry : XposedModule() {
    private val started = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var processName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookLog.attach(this)
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val app = OpenInApps.find(param.packageName) ?: return
        if (!started.add(app.pkg)) return
        // The process named after the package is the one that hosts its scanner.
        OpenInHooks.install(this, app, param.classLoader, processName == app.pkg)
    }

    /**
     * Runs in the old code when FerryQR is updated while a supported app is running. It lets go of
     * the app and hands over what the new code needs, so the update takes effect without a restart.
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(OpenInHooks.release())
        return true
    }

    /**
     * Runs in the new code. Package callbacks are not replayed after a hot reload, so the hooks are
     * set up again from what the old code handed over, once its own hooks are removed.
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        HookLog.attach(this)
        processName = param.processName
        super.onHotReloaded(param)
        OpenInHooks.resume(this, param.savedInstanceState)
    }
}
