package com.mio.irondome.escudo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConventionTargetsTest {

    @Test
    fun theRequestAsksForDadoQuandoEntaoAndTagsWithoutAKey() {
        val text = ConventionTargets.text("PedidoServiceTest", "PedidoServiceTest")

        assertTrue(text.contains("`PedidoServiceTest` está sem @DisplayName"))
        assertTrue(text.contains("Dado/Quando/Então"))
        assertTrue(text.contains("@Tag(\"criterio:ID\")"))
        assertTrue(text.contains("Não use chave entre colchetes"))
        assertFalse(text.contains("[Chave]"))
    }
}
