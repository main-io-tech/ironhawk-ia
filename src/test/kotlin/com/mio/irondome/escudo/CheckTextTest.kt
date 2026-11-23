package com.mio.irondome.escudo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** As verificações dos testes ditas em português: literais, e mudas diante do que não conhecem. */
class CheckTextTest {

    private fun said(code: String) = CheckText.describe(code)

    @Test
    fun assertJChainsBecomeOneSentencePerCheck() {
        assertEquals("checklist.tarefas tem 1 item", said("assertThat(checklist.getTarefas()).hasSize(1);"))
        assertEquals("checklist.tarefas tem 2 itens", said("assertThat(checklist.getTarefas()).hasSize(2)"))
        assertEquals("total é igual a 10", said("assertThat(total).isEqualTo(10)"))
        assertEquals("dia.deslocamentoEstimado é falso", said("assertThat(dia.isDeslocamentoEstimado()).isFalse()"))
        assertEquals(
            "erro é um ConflitoException e erro.codigo é igual a \"TAREFA_DO_MODELO_JA_EXISTE\"",
            said("assertThat(erro).isInstanceOf(ConflitoException.class).hasFieldOrPropertyWithValue(\"codigo\", \"TAREFA_DO_MODELO_JA_EXISTE\");"),
        )
        assertEquals("salvo.categorias tem só \"Celular\"", said("assertThat(salvo.getCategorias()).containsExactly(\"Celular\")"))
        assertEquals("nomes é exatamente \"a\", \"b\", nesta ordem", said("assertThat(nomes).containsExactly(\"a\", \"b\")"))
        assertEquals("itens não tem a chave \"trecho:t2\"", said("assertThat(itens).doesNotContainKey(\"trecho:t2\")"))
        assertEquals("a descrição não muda a frase", "total é zero", said("assertThat(total).as(\"o total, com vírgula, e (parênteses)\").isZero()"))
    }

    @Test
    fun extractingMovesTheCheckToTheField() {
        assertEquals("erros.codigo tem só \"FIM_ANTES_DO_INICIO\"", said("assertThat(erros).extracting(\"codigo\").containsExactly(\"FIM_ANTES_DO_INICIO\")"))
        assertEquals("tarefas.texto contém \"Passaporte\"", said("assertThat(tarefas).extracting(Tarefa::getTexto).contains(\"Passaporte\")"))
        assertEquals(
            "itens (id, editado) é exatamente (\"i1\", false), (\"i2\", true), nesta ordem",
            said("assertThat(itens).extracting(\"id\", \"editado\").containsExactly(tuple(\"i1\", false), tuple(\"i2\", true))"),
        )
        assertEquals(
            "erros.campo + \":\" + codigo tem só \"fim:CAMPO_OBRIGATORIO\"",
            said("assertThat(erros).extracting(e -> e.campo() + \":\" + e.codigo()).containsExactly(\"fim:CAMPO_OBRIGATORIO\")"),
        )
    }

    @Test
    fun theAssertionsInsideALambdaAreReadToo() {
        assertEquals(
            "erro é um DomainValidationException, e em algum item de e.erros, n.codigo é igual a \"CATEGORIA_REPETIDA\"",
            said(
                "assertThat(erro).isInstanceOfSatisfying(DomainValidationException.class, e -> assertThat(e.getErros())\n" +
                    "                .anySatisfy(n -> assertThat(n.codigo()).isEqualTo(\"CATEGORIA_REPETIDA\")));",
            ),
        )
    }

    @Test
    fun jUnitAndMockitoAreReadAsWell() {
        assertEquals("frete.total é igual a 10", said("assertEquals(10, frete.getTotal())"))
        assertEquals("lista.vazia é verdadeiro", said("Assertions.assertTrue(lista.vazia())"))
        assertEquals("lança IllegalArgumentException", said("assertThrows(IllegalArgumentException.class, () -> service.calcular(null))"))
        assertEquals("lança ConflitoException e com a mensagem contendo \"versão\"", said("assertThatThrownBy(() -> service.gravar(x)).isInstanceOf(ConflitoException.class).hasMessageContaining(\"versão\")"))
        assertEquals("chama repositorio.salvar(pedido)", said("verify(repositorio).salvar(pedido);"))
        assertEquals("não chama mongo.dropCollection(anyString())", said("verify(mongo, never()).dropCollection(anyString())"))
        assertEquals("chama mongo.executeCommand(comandos.capture) 3 vezes", said("verify(mongo, times(3)).executeCommand(comandos.capture())"))
        assertEquals("não usa mongo para mais nada", said("verifyNoMoreInteractions(mongo)"))
        assertEquals("total é igual a 10", said("total shouldBe 10"))
        assertEquals("espera a exceção IllegalStateException", said("espera a exceção IllegalStateException"))
    }

    @Test
    fun mockMvcExpectationsBecomeTheStatusAndTheJson() {
        assertEquals(
            "a resposta é 409 (conflito) e no JSON, \"$.codigo\" é \"TAREFA_REPETIDA\"",
            said("mvc.perform(post(\"/api/roteiros\")).andExpect(status().isConflict()).andExpect(jsonPath(\"$.codigo\").value(\"TAREFA_REPETIDA\"))"),
        )
    }

    @Test
    fun whatIsNotFullyKnownStaysAsCode() {
        assertNull("um verificador desconhecido no meio da cadeia", said("assertThat(total).isEqualTo(10).usandoAlgoDoProjeto()"))
        assertNull("um método de apoio da classe", said("roteiro.conferirStatus(200)"))
        assertNull("a instrução cortada pelo bloco", said("assertThat(x).satisfies(e ->"))
        assertNull("assertAll junta outras verificações", said("assertAll(() -> assertEquals(1, a), () -> assertEquals(2, b))"))
    }
}
