package com.mio.irondome.escudo

import com.intellij.openapi.project.Project
import com.mio.ironhawk.IronHawkFolder
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.interceptor.AgentTask
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * As duas ações de IA do Escudo sobre os critérios: gerar o catálogo a partir de uma fonte qualquer e ligar os testes
 * ao catálogo. Elas só tocam nos arquivos de critério e nas anotações dos testes; o aviso do fim traz Desfazer
 * Alterações da IA, como em toda ação da IA que escreve.
 */
object CriteriaActions {

    /** A pasta do catálogo, relativa à raiz do projeto. */
    const val FOLDER = "${IronHawkFolder.DEFENSE}/${CriteriaFiles.DIR}"
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** O contrato, como a IA precisa dele; é o mesmo de `docs/escudo-criterios.md`. */
    val CONTRACT: String = """
        ## O contrato

        **Catálogo.** Arquivos `.yaml` em `$FOLDER/`, um por assunto. Cada arquivo é uma lista de critérios:

        ```yaml
        - id: ROT-03
          titulo: Mover atividade para um dia que não existe é recusado e o roteiro não muda
          grupo: Roteiro          # opcional; agrupa na aba; sem ele vale o nome do arquivo
          origem: MAI-184         # opcional; texto livre (issue, spec, PRD)
          exige: [aceitacao]      # opcional; níveis que precisam cobrir; omitido = qualquer nível
        ```

        - `id` e `titulo` são obrigatórios. O `id` é único em todos os arquivos, sem espaços e sem `, ( ) & | !`.
        - `nenhum` é reservado e não pode ser id.
        - Cada valor fica numa linha só. Níveis: `unidade`, `integracao`, `aceitacao`.
        - O título diz o que deve ser verdade, em uma frase, em português.

        **Tags nos testes (JUnit 5).** Uma `@Tag("criterio:ID")` por critério coberto; `@Tag("criterio:nenhum")` quando
        nenhum critério se aplica (utilitário técnico); `@Tag("nivel:unidade")`, `nivel:integracao` ou
        `nivel:aceitacao` opcional. Com Cucumber, a mesma tag no Gherkin: `@criterio:ROT-03 @nivel:aceitacao`.

        **Nome do teste.** `@DisplayName` em Dado/Quando/Então, com `->` ou ENTÃO para o resultado esperado.
    """.trimIndent()

    fun generateInstructions(source: String, existingIds: List<String>): String = buildString {
        appendLine("# Gerar o catálogo de critérios de aceite")
        appendLine()
        appendLine("Converta a fonte abaixo em critérios de aceite no contrato do Escudo e grave os arquivos em `$FOLDER/`.")
        appendLine("A fonte pode ser um texto, o caminho de um arquivo do projeto (como um `spec.md`) ou um identificador de")
        appendLine("issue (Linear, Jira): se for um caminho, leia o arquivo; se for um identificador, consulte-o pelas")
        appendLine("ferramentas MCP disponíveis. Se não conseguir ler a fonte, diga isso e não invente critérios.")
        appendLine()
        appendLine(CONTRACT)
        appendLine()
        appendLine("## Regras")
        appendLine()
        appendLine("- Um critério é algo que dá para provar com um teste. Divida o que for amplo; junte o que for repetido.")
        appendLine("- Escolha um prefixo curto por assunto (`ROT`, `ACE`) e numere em sequência: `ROT-01`, `ROT-02`.")
        if (existingIds.isEmpty()) {
            appendLine("- O projeto ainda não tem catálogo.")
        } else {
            appendLine("- Já existem estes IDs, que não podem mudar nem repetir: ${existingIds.joinToString(", ")}.")
            appendLine("  Atualize o arquivo do assunto em vez de duplicar um critério que já existe.")
        }
        appendLine("- Preencha `origem` com o identificador da fonte, quando houver.")
        appendLine("- Use `exige` só quando o critério pede prova num nível específico.")
        appendLine("- **Altere só os arquivos `.yaml` de `$FOLDER/`.** Não mexa em código nem em testes.")
        appendLine()
        appendLine("## Fonte")
        appendLine()
        appendLine(source.trim())
    }

    fun alignInstructions(catalog: CriteriaCatalog): String = buildString {
        appendLine("# Ligar os testes aos critérios")
        appendLine()
        appendLine("Leia o catálogo em `$FOLDER/` e os testes do projeto, e deixe cada teste no padrão do Escudo.")
        appendLine()
        appendLine(CONTRACT)
        appendLine()
        appendLine("## Critérios do catálogo")
        appendLine()
        catalog.criteria.forEach { criterion ->
            val requires = criterion.requires.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.tag }?.let { " (exige: $it)" }.orEmpty()
            appendLine("- `${criterion.id}`: ${criterion.title}$requires")
        }
        appendLine()
        appendLine("## O que fazer")
        appendLine()
        appendLine("1. Para cada teste, acrescente `@Tag(\"criterio:ID\")` do critério que ele prova, a partir do que o teste")
        appendLine("   verifica. Um teste pode ter várias tags. Não repita uma tag que já existe.")
        appendLine("2. Acrescente `@Tag(\"nivel:...\")` quando o nível for evidente: `unidade` para uma classe isolada, `integracao`")
        appendLine("   com banco, fila ou outro componente real, `aceitacao` para o fluxo de negócio de ponta a ponta.")
        appendLine("3. Deixe o `@DisplayName` em Dado/Quando/Então, mantendo o sentido. Retire a chave `[Chave]` do início, se houver.")
        appendLine("   Classe de teste sem `@DisplayName` ganha um que diga, em português, o que ela testa.")
        appendLine("4. Nos arquivos `.feature`, ponha `@criterio:ID` e `@nivel:aceitacao` acima do `Cenário`.")
        appendLine("5. Se não houver como saber a que critério um teste se liga, use `@Tag(\"criterio:nenhum\")` quando for um")
        appendLine("   teste técnico, ou deixe sem tag e liste-o na resposta. **Não chute** um critério.")
        appendLine("6. Se um critério do catálogo não tem teste, não escreva teste: só liste na resposta.")
        appendLine()
        appendLine("**Altere só anotações, tags e `@DisplayName`.** Não mude a lógica dos testes, nem o código de produção, nem o catálogo.")
        appendLine("Acrescente os imports de `org.junit.jupiter.api.Tag` e `DisplayName` se faltarem.")
    }

    /** Pede à IA o catálogo a partir de [source]; devolve se a execução começou. Na EDT. */
    fun generate(project: Project, source: String, existingIds: List<String>): Boolean {
        val runner = AgentRunner.getInstance(project)
        val file = runner.writeInstructions("criterios-gerar-${stamp()}.md", generateInstructions(source, existingIds)) ?: return false
        return runner.run(
            AgentTask(
                "Gerar critérios de aceite",
                "Gere o catálogo de critérios de aceite descrito em $file, seguindo as instruções do arquivo.",
                write = true,
                origin = TestsTab.TITLE,
            ),
        )
    }

    /** Pede à IA para ligar os testes ao [catalog]; devolve se a execução começou. Na EDT. */
    fun align(project: Project, catalog: CriteriaCatalog): Boolean {
        val runner = AgentRunner.getInstance(project)
        val file = runner.writeInstructions("criterios-ligar-${stamp()}.md", alignInstructions(catalog)) ?: return false
        return runner.run(
            AgentTask(
                "Ligar os testes aos critérios",
                "Ligue os testes aos critérios de aceite como descrito em $file, seguindo as instruções do arquivo.",
                write = true,
                origin = TestsTab.TITLE,
            ),
        )
    }

    private fun stamp() = LocalDateTime.now().format(FILE_TIME)
}
