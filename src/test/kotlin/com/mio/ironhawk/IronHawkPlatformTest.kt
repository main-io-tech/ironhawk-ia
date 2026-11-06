package com.mio.ironhawk

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.Disposer
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.mio.irondome.interceptor.AgentProvider
import com.mio.irondome.interceptor.AgentRunMode
import com.mio.irondome.interceptor.AgentSettings
import com.mio.tomahawk.run.RunMode
import com.mio.tomahawk.run.TomahawkSettings
import java.nio.file.Files
import com.mio.tomahawk.run.AgentProvider as AttackProvider

class IronHawkPlatformTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgentSettings.provider = AgentProvider.CURSOR
            AgentSettings.executable = null
            AgentSettings.model = null
            AgentSettings.clearVerified()
            IronHawkSystem.entries.forEach { it.enabled = true }
        } finally {
            super.tearDown()
        }
    }

    fun testTheAttackUsesTheSharedArsenal() {
        val cli = Files.createTempFile("claude", ".cmd")
        try {
            AgentSettings.provider = AgentProvider.CLAUDE
            AgentSettings.executable = cli.toString()
            assertEquals(AttackProvider.CLAUDE, TomahawkSettings.provider)
            assertFalse(TomahawkSettings.ready)

            TomahawkSettings.markVerified("06/11/2026 18:00")
            assertTrue("um teste vale para os dois sistemas", AgentSettings.ready && TomahawkSettings.ready)

            TomahawkSettings.model = "sonnet"
            assertEquals("sonnet", AgentSettings.model)
            assertFalse("mudar a IA pelo Ataque invalida o teste dos dois", AgentSettings.ready || TomahawkSettings.ready)
        } finally {
            Files.deleteIfExists(cli)
        }
    }

    fun testAntigravityServesTheDefenseButNotTheMissions() {
        val cli = Files.createTempFile("agy", ".cmd")
        try {
            AgentSettings.provider = AgentProvider.ANTIGRAVITY
            AgentSettings.executable = cli.toString()
            AgentSettings.markVerified("06/11/2026 18:00")
            assertTrue(AgentSettings.ready)
            assertFalse(TomahawkSettings.supported)
            assertNull("o Spec Kit não conhece o Antigravity", TomahawkSettings.launch())
            assertFalse(TomahawkSettings.ready)
        } finally {
            Files.deleteIfExists(cli)
        }
    }

    fun testTheTomahawkConfigurationMovesToTheArsenalOnce() {
        val props = PropertiesComponent.getInstance()
        try {
            props.unsetValue("IronHawk.Arsenal.Migrated")
            props.setValue("Tomahawk.Agent.Provider", "CLAUDE")
            props.setValue("Tomahawk.Agent.Model", "opus")
            ArsenalMigration.run()
            assertEquals(AgentProvider.CLAUDE, AgentSettings.provider)
            assertEquals("opus", AgentSettings.model)

            AgentSettings.model = null
            props.setValue("Tomahawk.Agent.Model", "haiku")
            ArsenalMigration.run()
            assertNull("só uma vez", AgentSettings.model)
        } finally {
            props.unsetValue("Tomahawk.Agent.Provider")
            props.unsetValue("Tomahawk.Agent.Model")
        }
    }

    fun testTheArsenalShowsOnlyTheSystemsThatAreOn() {
        AgentSettings.autoSetupTried = true
        val tab = ArsenalWeb(project)
        try {
            val both = tab.html()
            assertTrue("Contenção da IA" in both && "Proteções das missões" in both)
            assertEquals("uma escolha só de onde rodar a IA", 1, Regex("name=\"runMode\"").findAll(both).count())

            IronHawkSystem.DEFENSE.enabled = false
            val attack = tab.html()
            assertTrue("Proteções das missões" in attack && "Contenção da IA" !in attack)
            assertTrue("a IA comum continua", "CLI de IA" in attack && "Onde rodar a IA" in attack)

            IronHawkSystem.DEFENSE.enabled = true
            IronHawkSystem.ATTACK.enabled = false
            val defense = tab.html()
            assertTrue("Contenção da IA" in defense && "Proteções das missões" !in defense && "Spec Kit" !in defense)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun testBothSystemsRunTheAiInTheSamePlace() {
        try {
            AgentSettings.runMode = AgentRunMode.TERMINAL
            assertEquals(RunMode.TERMINAL, TomahawkSettings.runMode)
            TomahawkSettings.runMode = RunMode.ASK
            assertEquals(AgentRunMode.ASK, AgentSettings.runMode)
        } finally {
            AgentSettings.runMode = AgentRunMode.BACKGROUND
        }
    }

    fun testTheTomahawkRunModeMovesOnlyIfTheDefenseNeverChose() {
        val props = PropertiesComponent.getInstance()
        try {
            props.unsetValue("IronHawk.Arsenal.RunModeMigrated")
            props.setValue("Tomahawk.Agent.RunMode", "TERMINAL")
            ArsenalMigration.run()
            assertEquals(AgentRunMode.TERMINAL, AgentSettings.runMode)

            props.unsetValue("IronHawk.Arsenal.RunModeMigrated")
            AgentSettings.runMode = AgentRunMode.ASK
            ArsenalMigration.run()
            assertEquals("a escolha da Defesa fica", AgentRunMode.ASK, AgentSettings.runMode)
        } finally {
            props.unsetValue("Tomahawk.Agent.RunMode")
            AgentSettings.runMode = AgentRunMode.BACKGROUND
        }
    }

    fun testTheWindowSwitchesSystemsAndKeepsTheArsenalLast() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        val window = IronHawkWindow.getInstance(project)
        window.install(toolWindow)
        val titles = { toolWindow.contentManager.contents.map { it.displayName } }

        window.select(IronHawkSystem.ATTACK)
        assertEquals(listOf("Lançador", "Avaliação", "Telemetria"), titles().take(3))
        assertEquals("Arsenal", titles().last())
        assertFalse("Radar" in titles())

        window.select(IronHawkSystem.DEFENSE)
        assertEquals(listOf("Radar", "Sentinela"), titles().take(2))
        assertEquals("Arsenal", titles().last())
        assertFalse("Lançador" in titles())
        assertEquals(1, titles().count { it == "Arsenal" })

        IronHawkSystem.DEFENSE.enabled = false
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("desligar o sistema à vista mostra o outro", IronHawkSystem.ATTACK, window.current)
        assertFalse("Radar" in titles())
        assertEquals("Arsenal", titles().last())
    }
}
