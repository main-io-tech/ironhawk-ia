package com.mio.irondome.interceptor

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.mio.ironhawk.ArsenalPanel
import com.mio.ironhawk.allTabsText
import com.mio.ironhawk.panelText
import com.mio.ironhawk.IronHawkSystem
import java.nio.file.Files

class AgentSettingsPlatformTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgentSettings.provider = AgentProvider.CURSOR
            AgentSettings.executable = null
            AgentSettings.model = null
            AgentSettings.clearVerified()
        } finally {
            super.tearDown()
        }
    }

    fun testTheAiIsReadyOnlyAfterATestWithTheCurrentConfiguration() {
        val cli = Files.createTempFile("agent", ".cmd")
        AgentSettings.provider = AgentProvider.CURSOR
        AgentSettings.executable = cli.toString()
        AgentSettings.model = "gpt-5"
        assertFalse("sem teste, os botões ficam desligados", AgentSettings.ready)

        AgentSettings.markVerified("03/11/2026 21:00")
        assertTrue(AgentSettings.ready)
        assertEquals("03/11/2026 21:00", AgentSettings.verifiedAt)

        AgentSettings.model = "claude"
        assertFalse("outro modelo invalida o teste", AgentSettings.ready)
        assertNull(AgentSettings.verifiedAt)

        AgentSettings.markVerified("03/11/2026 21:05")
        AgentSettings.provider = AgentProvider.ANTIGRAVITY
        assertFalse("outro CLI também", AgentSettings.ready)

        AgentSettings.provider = AgentProvider.CURSOR
        assertTrue("voltar à configuração testada volta a valer", AgentSettings.ready)
        Files.delete(cli)
        AgentSettings.model = "claude"
        assertFalse("sem o executável, não está pronta", AgentSettings.ready)
    }

    fun testExtraArgsInvalidateTheTest() {
        val cli = Files.createTempFile("agent", ".cmd")
        try {
            AgentSettings.provider = AgentProvider.CURSOR
            AgentSettings.executable = cli.toString()
            AgentSettings.markVerified("04/11/2026 16:00")
            assertTrue(AgentSettings.ready)

            AgentSettings.extraArgs = "--approve-mcps"
            assertFalse("outros argumentos invalidam o teste", AgentSettings.ready)
            AgentSettings.extraArgs = null
            assertTrue(AgentSettings.ready)
        } finally {
            AgentSettings.extraArgs = null
            Files.deleteIfExists(cli)
        }
    }

    fun testTheArsenalShowsEveryCliAndTheState() {
        AgentSettings.autoSetupTried = true
        IronHawkSystem.ATTACK.enabled = false
        val tab = ArsenalPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching("o Arsenal não terminou de ler o disco", { tab.probed }, 30)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val html = allTabsText(tab)
            AgentProvider.entries.forEach { assertTrue("${it.title} no seletor", it.title in html) }
            assertTrue("Configuração automática" in html)
            assertTrue("tempo máximo e argumentos ficam recolhidos", "Opções avançadas" in html && "Argumentos extras" !in html)
            assertTrue("sem o texto de divulgação", "O que a IA libera" !in html)
            assertTrue("as flags ficam recolhidas", "acceptEdits" !in html)
            assertTrue("a contenção aparece no Arsenal", "Contenção da IA" in html && "Desfazer Alterações da IA" in html)
            val ask = UIUtil.findComponentsOfType(tab, JBCheckBox::class.java).single { it.text.startsWith("Perguntar antes de cada tarefa") }
            assertTrue(ask.isSelected)

            UIUtil.findComponentsOfType(tab, ActionLink::class.java).single { it.text == ArsenalPanel.DETAILS_CLOSED }.doClick()
            val details = allTabsText(tab)
            assertTrue("sem o Ataque, nada de permissão total", "acceptEdits" in details && "bypassPermissions" !in details)

            AgentSettings.confirmWrites = false
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse("dá para voltar a perguntar pela mesma caixa",
                UIUtil.findComponentsOfType(tab, JBCheckBox::class.java).single { it.text.startsWith("Perguntar antes de cada tarefa") }.isSelected)
        } finally {
            AgentSettings.confirmWrites = true
            IronHawkSystem.ATTACK.enabled = true
            com.intellij.openapi.util.Disposer.dispose(tab)
        }
    }

    fun testTranscriptSummaryFeedsTheRunsList() {
        val (title, status) = AgentTranscript.summary(listOf("# IA: Revisão do PR #12", "", "- **Quando:** agora", "- **Situação:** concluída"))

        assertEquals("Revisão do PR #12", title)
        assertEquals("concluída", status)
        assertEquals(null to null, AgentTranscript.summary(listOf("outro arquivo")))
    }
}
