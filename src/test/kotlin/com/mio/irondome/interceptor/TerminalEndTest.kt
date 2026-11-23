package com.mio.irondome.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEndTest {

    @Test
    fun theExitMarkerIsWrittenAfterTheCommandInEachShell() {
        assertEquals(
            "& 'claude' 'oi'; Set-Content -LiteralPath 'C:\\p\\r.md.fim' -Value \$LASTEXITCODE",
            AgentCommand.withExitMarker("& 'claude' 'oi'", "C:\\p\\r.md.fim", ShellKind.POWERSHELL),
        )
        assertEquals("claude oi & echo fim> \"C:\\p\\r.md.fim\"", AgentCommand.withExitMarker("claude oi", "C:\\p\\r.md.fim", ShellKind.CMD))
        assertEquals("'claude' 'oi'; echo \$? > '/p/r.md.fim'", AgentCommand.withExitMarker("'claude' 'oi'", "/p/r.md.fim", ShellKind.POSIX))
    }

    @Test
    fun theMessageSaysWhetherTheCliEndedOrTheTabWasClosed() {
        assertEquals("O terminal foi fechado antes de a resposta chegar.", TerminalEnd.describe(exited = false, code = null))
        val failed = TerminalEnd.describe(exited = true, code = "1\r\n")
        assertTrue(failed, "com o código 1" in failed && "sem gravar a resposta" in failed)
        assertFalse("código 0 ou ilegível não aparece", "código" in TerminalEnd.describe(exited = true, code = "fim"))
        assertFalse("código" in TerminalEnd.describe(exited = true, code = "0"))
    }
}
