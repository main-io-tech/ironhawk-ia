package com.mio.irondome.caixapreta

import com.mio.irondome.base.TabFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {

    private val healthy = Environment(
        pluginVersion = "0.1.0",
        ide = "IntelliJ IDEA 2025.2 (build IU-252.1)",
        system = "Windows 11, Java 21",
        junitPlugin = true,
        githubPlugins = true,
        vcs = listOf("Git"),
        extraTabs = setOf("Escudo", "Patrulha"),
        tabFailures = emptyMap(),
        githubAccount = true,
        agentCli = "C:/Users/dev/.local/bin/agent.exe",
    )

    private fun check(env: Environment, mechanism: Mechanism) = Compatibility.evaluate(env).single { it.mechanism == mechanism }

    @Test
    fun everythingAvailableHasNoProblems() {
        val checks = Compatibility.evaluate(healthy)

        assertTrue(checks.all { it.status == CheckStatus.OK })
        assertTrue(Compatibility.problems(checks).isEmpty())
    }

    @Test
    fun withTheDefenseOffOnlyTheSharedMechanismsWarn() {
        val env = healthy.copy(extraTabs = setOf("Escudo", "Patrulha"), agentCli = null)

        val attackOnly = Compatibility.relevant(Compatibility.evaluate(env), defense = false)

        assertTrue(attackOnly.none { it.mechanism == Mechanism.ESCUDO || it.mechanism == Mechanism.RADAR })
        assertTrue(attackOnly.any { it.mechanism == Mechanism.INTERCEPTOR } && attackOnly.any { it.mechanism == Mechanism.CAIXA_PRETA })
        assertTrue(Compatibility.problems(attackOnly).isEmpty())
        assertEquals(Compatibility.evaluate(env), Compatibility.relevant(Compatibility.evaluate(env), defense = true))
    }

    @Test
    fun theFailedTabOpensTheDiagnosticsOnTheErrorLog() {
        assertEquals(DiagnosticsTab.ERROR_LOG.name, com.mio.irondome.base.TabErrorPanel.ERROR_LOG_TAB)
    }

    @Test
    fun theCliHintPointsToTheIronHawkSettings() {
        val hint = check(healthy.copy(agentCli = null), Mechanism.INTERCEPTOR).fix.orEmpty()

        assertTrue(hint, "IronHawk IA > Arsenal (IA)" in hint && "Iron Dome" !in hint)
    }

    @Test
    fun aTabThatFailedToOpenShowsTheError() {
        val env = healthy.copy(tabFailures = mapOf("Patrulha" to "IllegalStateException: JCEF falhou"))

        val impact = check(env, Mechanism.PATRULHA)

        assertEquals(CheckStatus.ERROR, impact.status)
        assertTrue(impact.detail.contains("IllegalStateException: JCEF falhou"))
        assertEquals(CheckStatus.OK, check(env, Mechanism.ESCUDO).status)
    }

    @Test
    fun withoutJUnitTheShieldIsUnavailable() {
        assertEquals(CheckStatus.ERROR, check(healthy.copy(junitPlugin = false, extraTabs = setOf("Patrulha")), Mechanism.ESCUDO).status)
    }

    @Test
    fun withoutTheGitHubPluginsThePatrolIsOptional() {
        val env = healthy.copy(githubPlugins = false, extraTabs = setOf("Escudo"))

        assertEquals(CheckStatus.INFO, check(env, Mechanism.PATRULHA).status)
        assertTrue(Compatibility.problems(Compatibility.evaluate(env)).isEmpty())
    }

    @Test
    fun optionalSettingsAreInformationOnlyAndDoNotWarn() {
        val env = healthy.copy(vcs = emptyList(), githubAccount = false, agentCli = null)

        assertEquals(CheckStatus.INFO, check(env, Mechanism.SENTINELA).status)
        assertEquals(CheckStatus.INFO, check(env, Mechanism.CAIXA_PRETA).status)
        assertEquals(CheckStatus.INFO, check(env, Mechanism.INTERCEPTOR).status)
        assertTrue(check(env, Mechanism.INTERCEPTOR).fix!!.contains("Arsenal (IA)"))
        assertTrue(Compatibility.problems(Compatibility.evaluate(env)).isEmpty())
    }

    @Test
    fun markdownListsTheChecksAndTheLogErrors() {
        val env = healthy.copy(tabFailures = mapOf("Patrulha" to "IllegalStateException: boom"))
        val error = PluginError(
            "2026-11-03 13:00:00", "WARN", "A aba Patrulha do Iron Dome IA não pôde ser aberta",
            listOf("java.lang.IllegalStateException: boom", "\tat com.mio.irondome.patrulha.PatrulhaPanel.<init>(PatrulhaPanel.kt:72)"),
        )

        val text = Compatibility.markdown(env, Compatibility.evaluate(env), listOf(error))

        assertTrue(text.contains("| Patrulha | Indisponível |"))
        assertTrue(text.contains("### 2026-11-03 13:00:00 · WARN: A aba Patrulha"))
        assertTrue(text.contains("com.mio.irondome.patrulha.PatrulhaPanel.<init>"))
    }

    @Test
    fun tabFailureDescriptionUsesTheFirstLineOfTheMessage() {
        assertEquals("IllegalStateException: primeira", TabFailures.describe(IllegalStateException("primeira\nsegunda")))
        assertEquals("NoClassDefFoundError", TabFailures.describe(NoClassDefFoundError()))
    }
}
