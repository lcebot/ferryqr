package io.github.lcebot.ferryqr.openin

import io.github.lcebot.ferryqr.dispatch.Targets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A supported app missing from scope.list never loads the module, and one missing from the
 * manifest's queries can neither be launched nor reached, with no error anywhere in either case.
 */
class OpenInAppsTest {
    private val packages = OpenInApps.all.map { it.pkg }

    @Test
    fun scopeListsEveryApp() {
        val scope = File("src/main/resources/META-INF/xposed/scope.list").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(packages.toSet(), scope.toSet())
    }

    @Test
    fun moduleRepoListsTheScope() {
        val scope = File("../lsposed-repo/SCOPE").readText().trim().removeSurrounding("[", "]")
            .split(',').map { it.trim().removeSurrounding("\"") }
        assertEquals(packages.toSet(), scope.toSet())
    }

    @Test
    fun manifestQueriesEveryApp() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (pkg in packages) assertTrue("<package android:name=\"$pkg\" />" in manifest, "$pkg is not queried")
    }

    @Test
    fun everyAppCanBeOffered() {
        for (pkg in packages) assertTrue(Targets.of(pkg)?.viaModule == true, "$pkg is not offered through the helper")
    }

    @Test
    fun replacedArgumentsAreDistinctParameters() {
        for (app in OpenInApps.all) {
            val s = app.strategy as? Strategy.ReplaceResult ?: continue
            val args = listOf(s.textArg, s.dataArg, s.charsetArg, s.typeIdArg, s.typeNameArg)
            assertEquals(args.size, args.toSet().size, "${app.pkg} reuses an argument")
            assertTrue(args.all { it in s.params.indices }, "${app.pkg} names an argument it does not have")
            assertTrue(OpenInApps.DEFAULT_TYPE in s.types, "${app.pkg} has no default type")
        }
    }
}
