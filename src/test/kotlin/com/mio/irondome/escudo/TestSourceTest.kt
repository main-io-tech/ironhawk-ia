package com.mio.irondome.escudo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSourceTest {

    @Test
    fun theIdComesFromTheRunnerUrlWithoutParameters() {
        assertEquals("loja.FreteTest#calcula", TestSource.idOf("java:test://loja.FreteTest/calcula"))
        assertEquals("loja.FreteTest#calcula", TestSource.idOf("java:test://loja.FreteTest/calcula(int)[2]"))
    }

    @Test
    fun readsTheChecksOfAJavaTestOneLineEach() {
        val evidence = TestSource.evidence(
            "t",
            "recusa",
            """
            @Test
            @DisplayName("DADO um roteiro, QUANDO move, ENTÃO recusa")
            void recusa() {
                var roteiro = roteiroComUmDia(); // assertEquals(1, 2) num comentário não conta
                var erro = assertThrows(DiaInexistente.class,
                        () -> roteiro.mover("Coliseu", 5));
                assertEquals("DIA_DESTINO_INEXISTENTE", erro.codigo());
                assertThat(roteiro.dias())
                        .hasSize(1)
                        .containsExactly(primeiro);
                String texto = "assertEquals(a, b);";
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "var erro = assertThrows(DiaInexistente.class, () -> roteiro.mover(\"Coliseu\", 5))",
                "assertEquals(\"DIA_DESTINO_INEXISTENTE\", erro.codigo())",
                "assertThat(roteiro.dias()) .hasSize(1) .containsExactly(primeiro)",
            ),
            evidence.checks,
        )
    }

    @Test
    fun readsTheChecksOfAKotlinTestWithoutSemicolons() {
        val evidence = TestSource.evidence(
            "t",
            "soma",
            """
            @Test
            fun `soma dois valores`() = runTest {
                val total = calculadora.soma(2, 3)
                assertEquals(5, total)
                total shouldBe 5
                verify(exactly = 1) { auditoria.registra(any()) }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("assertEquals(5, total)", "total shouldBe 5", "verify(exactly = 1) { … }"), evidence.checks)
    }

    @Test
    fun aTestWithoutChecksHasNoProofAndTheJUnit4ExpectedCounts() {
        assertTrue(TestSource.evidence("t", "calcula", "@Test void calcula() { servico.calcula(10); }").checks.isEmpty())
        assertEquals(
            "o expected do JUnit 4 é uma verificação",
            listOf("espera a exceção IllegalStateException"),
            TestSource.evidence("t", "falha", "@Test(expected = IllegalStateException.class) public void falha() { servico.calcula(-1); }").checks,
        )
    }

    @Test
    fun aHelperOfTheClassThatChecksCountsAsACheck() {
        val helpers = mapOf(
            "confereRoteiro" to "private void confereRoteiro(Roteiro r) { assertEquals(1, r.dias().size()); }",
            "monta" to "private Roteiro monta() { return new Roteiro(); }",
        )
        val evidence = TestSource.evidence("t", "mantém", "@Test void mantem() { var r = monta(); confereRoteiro(r); }", helpers)

        assertEquals(listOf("confereRoteiro(r)"), evidence.checks)
    }

    @Test
    fun aStepOfTheActorIsShownInWordsWithTheAssertionsItHasInside() {
        val actor = mapOf(
            "conferirRecusa" to """
                public void conferirRecusa(String codigo) {
                    assertThat(json.readTree(ultimaResposta.getContentAsByteArray()).path("codigo").asString()).isEqualTo(codigo);
                }
            """.trimIndent(),
            "confereDestinoAusente" to "public void confereDestinoAusente() { roteiro.conferirRecusa(\"DIA_NAO_ENCONTRADO\"); }",
            "conferirAtividades" to "public void conferirAtividades(int dia, String... titulos) { assertThat(atividadesDoDia(dia)).containsExactly(titulos); }",
            "tentaMover" to "public void tentaMover() { roteiro.enviar(patch(\"/dias\"), null, 404); }",
        )
        val name = "DADO uma visita, QUANDO Ana tenta mover para um dia ausente, ENTÃO recusa com DIA_NAO_ENCONTRADO"
        val evidence = TestSource.evidence(
            "t",
            name,
            """
            @Test
            void recusa() throws Exception {
                ana.criaRoteiro();

                anaNaAtividade.tentaMover();

                anaNaAtividade.confereDestinoAusente();
                ana.conferirAtividades(1, "Visita ao Coliseu", "Jantar");
            }
            """.trimIndent(),
            external = actor,
        )

        assertEquals(listOf("anaNaAtividade.confereDestinoAusente()", "ana.conferirAtividades(1, \"Visita ao Coliseu\", \"Jantar\")"), evidence.checks)
        val (ausente, atividades) = evidence.proof
        assertTrue(ausente.step && atividades.step)
        assertEquals("confere destino ausente", ausente.text)
        assertEquals("a asserção vem do método que o passo chama, com o argumento no lugar do parâmetro", 1, ausente.inner.size)
        assertTrue(ausente.inner.single().text, "\"DIA_NAO_ENCONTRADO\"" in ausente.inner.single().text)
        assertEquals("conferir atividades: 1, Visita ao Coliseu, Jantar", atividades.text)
        assertEquals("atividadesDoDia(1) é exatamente \"Visita ao Coliseu\", \"Jantar\", nesta ordem", atividades.inner.single().text)
    }

    @Test
    fun aStepIsJudgedByItsBodyAndAnAssertionNamedMethodStillCountsByItsName() {
        val actor = mapOf(
            "registraTotal" to "void registraTotal() { total = pedido.total(); }",
            "confereTotal" to "void confereTotal(int esperado) { if (total != esperado) throw new AssertionError(\"total errado\"); }",
            "assertPedido" to "void assertPedido() { comparador.compara(pedido); }",
        )
        fun checks(body: String) = TestSource.evidence("t", "soma", "@Test void soma() { $body }", external = actor)

        assertTrue("um método do projeto sem asserção não é verificação", checks("ana.registraTotal();").checks.isEmpty())
        assertEquals("uma asserção escrita à mão conta", listOf("ana.confereTotal(10)"), checks("ana.confereTotal(10);").checks)
        val named = checks("ana.assertPedido();")
        assertEquals("o nome de asserção vale, como o de uma biblioteca", listOf("ana.assertPedido()"), named.checks)
    }

    @Test
    fun thePreparationAndTheActionThatAlsoAssertAreKeptApartFromWhatTheTestVerifies() {
        val actor = mapOf(
            "criaRoteiro" to "void criaRoteiro() { enviar(post(\"/roteiros\")); assertThat(status()).isEqualTo(201); }",
            "conferirTitulo" to "void conferirTitulo(String titulo) { assertThat(dias()).extracting(Dia::titulo).anyMatch(t -> t.equals(titulo)); }",
            "temTitulo" to "void temTitulo(String titulo) { assertThat(lista()).anyMatch(titulo -> titulo.isBlank()); assertThat(titulo()).isEqualTo(titulo); }",
        )
        val both = TestSource.evidence("t", "cria", "@Test void cria() { ana.criaRoteiro(); ana.conferirTitulo(\"Roma\"); }", external = actor)

        assertEquals(listOf(true, false), both.proof.map { it.support })
        assertEquals(
            "o argumento entra no lugar do parâmetro, sem mexer na referência de método de mesmo nome",
            "assertThat(dias()).extracting(Dia::titulo).anyMatch(t -> t.equals(\"Roma\"))",
            both.proof.last().inner.single().text,
        )

        val alone = TestSource.evidence("t", "cria", "@Test void cria() { ana.criaRoteiro(); ana.temTitulo(\"Roma\"); }", external = actor)
        assertEquals("sem passo com nome de conferência, todos valem como verificação", listOf(false, false), alone.proof.map { it.support })
        assertEquals(
            "um lambda com o mesmo nome esconde o parâmetro, e uma chamada com o mesmo nome não é o parâmetro",
            listOf("assertThat(lista()).anyMatch(titulo -> titulo.isBlank())", "titulo() é igual a \"Roma\""),
            alone.proof.last().inner.map { it.text },
        )
    }

    @Test
    fun theStepIsReadInWords() {
        assertEquals("cria roteiro com visita ao coliseu e segundo dia livre", TestSource.words("criaRoteiroComVisitaAoColiseuESegundoDiaLivre"))
        assertEquals("conferir recusa: DIA_NAO_ENCONTRADO", TestSource.stepLabel("ana.conferirRecusa(\"DIA_NAO_ENCONTRADO\")"))
        assertEquals("confere", TestSource.stepLabel("confere()"))
        assertEquals("confere: frete, 2", TestSource.stepLabel("this.confere(frete, 2)"))
        assertNull("uma cadeia de chamadas não é um passo só", TestSource.stepLabel("await().untilAsserted(x)"))
        assertNull(TestSource.stepLabel("var x = ana.confere()"))
    }

}
