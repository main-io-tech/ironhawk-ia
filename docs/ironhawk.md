<p align="center">
  <img src="../src/main/resources/META-INF/pluginIcon.svg" width="110" alt="Iron Dome IA">
</p>

<h1 align="center">Iron Dome IA</h1>

<p align="center">
  <b>Um sistema de defesa para o seu código, direto no IntelliJ.<br>
  Você marca o alvo, a IA intercepta e nenhum bug passa pelo domo.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/IntelliJ%20IDEA-2025.2%2B-000000?logo=intellijidea&logoColor=white" alt="IntelliJ IDEA 2025.2+">
  <img src="https://img.shields.io/badge/Java%20%C2%B7%20Kotlin%20%C2%B7%20qualquer%20texto-7F52FF?logo=kotlin&logoColor=white" alt="Java, Kotlin e qualquer arquivo de texto">
  <img src="https://img.shields.io/badge/funciona%20com-Cursor%20%C2%B7%20Claude%20Code%20%C2%B7%20Codex%20%C2%B7%20qualquer%20IA-8250DF" alt="Funciona com Cursor, Claude Code, Codex e qualquer IA">
  <img src="https://img.shields.io/badge/vers%C3%A3o-0.1.0-0969DA" alt="Versão 0.1.0">
</p>

<p align="center">
  <a href="#como-funciona">Como funciona</a> &nbsp;·&nbsp;
  <a href="#radar">Radar</a> &nbsp;·&nbsp;
  <a href="#centro-de-comando">Centro de Comando</a> &nbsp;·&nbsp;
  <a href="#raio-de-impacto">Raio de Impacto</a> &nbsp;·&nbsp;
  <a href="#sentinela">Sentinela</a> &nbsp;·&nbsp;
  <a href="#escudo">Escudo</a> &nbsp;·&nbsp;
  <a href="#patrulha">Patrulha</a> &nbsp;·&nbsp;
  <a href="#arsenal">Arsenal</a> &nbsp;·&nbsp;
  <a href="#instalacao">Instalação</a> &nbsp;·&nbsp;
  <a href="#configure-sua-ia">Configure sua IA</a> &nbsp;·&nbsp;
  <a href="#faq">FAQ</a>
</p>

<p align="center">
  <img src="images/fluxo.svg" width="900" alt="Fluxo do Iron Dome IA: marque o alvo no editor, envie as ordens em Markdown e a IA intercepta e marca como resolvido">
</p>

---

## ✨ Por que o Iron Dome IA?

Pedir correções para a IA pelo chat é atirar no escuro: você descreve o lugar do problema, ela
procura no lugar errado, esquece metade dos pedidos e altera o que você só queria discutir.

O **Iron Dome IA** traz para dentro da IDE o fluxo que todo time já conhece: o **code review**.
Você marca os alvos linha a linha, como num pull request, e o plugin transforma tudo em ordens de
interceptação em Markdown, com arquivo, linha e trecho citado. A IA segue as ordens, marca cada
alvo como resolvido e você vê o resultado em verde no editor.

| 💣 Sem o domo | 🛡️ Com o Iron Dome IA |
| --- | --- |
| "Corrige aquela função do pedido..." e a IA procura no lugar errado | Cada comentário leva o arquivo, a linha e o trecho de código exato |
| Você pede cinco ajustes e só três são feitos | Um checklist em Markdown: a IA marca `[x]` em cada item resolvido |
| A IA altera até o que você só queria discutir | Você escolhe, por comentário: **aplicar direto** ou **discutir antes** |
| Pedidos que se contradizem viram fogo amigo | A IA é instruída a parar e perguntar quando comentários se contradizem |
| As revisões antigas se acumulam na pasta do projeto | Um clique consolida o histórico num único arquivo, ou limpa tudo |
| Você mexe numa classe sem saber o que pode quebrar | O radar de dependências mostra quem depende dela, o risco e os testes antes da mudança |
| Os testes viram uma lista sem ordem, com nomes de método | O Escudo agrupa os testes pela chave do `@DisplayName` e destaca cada parte do nome |

<a id="como-funciona"></a>

## ⚡ Como funciona

<table>
  <tr>
    <td align="center" width="33%">
      <h3>🎯 1. Marque o alvo</h3>
      Clique no <b>+</b> da margem, dê duplo clique no número da linha ou use
      <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd>. Escolha o modo:
      <i>Aplicar direto</i> ou <i>Discutir antes de alterar</i>.
    </td>
    <td align="center" width="33%">
      <h3>📨 2. Envie as ordens</h3>
      <b>Disparar Este Arquivo</b> ou <b>Disparar Todos</b> (<kbd>Ctrl</kbd>+<kbd>S</kbd>) gera
      <code>ironhawk/iron-dome/ordem-interceptacao-&lt;id&gt;.md</code> com cada comentário e o trecho de código.
    </td>
    <td align="center" width="33%">
      <h3>💥 3. A IA intercepta</h3>
      Ela aplica as correções, marca <code>[x]</code> em cada alvo abatido e o plugin mostra o
      comentário em verde, como resolvido.
    </td>
  </tr>
</table>

## 🧩 Mecanismos de defesa

Cada mecanismo é independente e tem uma aba própria no painel lateral **Iron Dome IA**.

| Mecanismo | Missão | Onde fica |
| --- | --- | --- |
| 📡 [**Radar**](#radar) | Marca os alvos: comentários inline que acompanham o código, e guarda o histórico de revisões. | Editor e aba Radar |
| 🎯 [**Centro de Comando**](#centro-de-comando) | Transforma os alvos em ordens de interceptação em Markdown e evita o fogo amigo. | Barra do editor e `ironhawk/iron-dome/` |
| 💥 [**Raio de Impacto**](#raio-de-impacto) | O diagrama de classes da classe aberta e das que dependem dela ou de que ela depende. | Aba Raio de Impacto |
| 👁️ [**Sentinela**](#sentinela) | Vigia o perímetro: tudo o que ainda não foi commitado, com o diff a um clique. | Aba Sentinela |
| 🛡️ [**Escudo**](#escudo) | Executa os testes JUnit e mostra o resultado agrupado e com os nomes formatados. | Aba Escudo |
| 🛰️ [**Patrulha**](#patrulha) | Revisa os pull requests abertos no GitHub no modo revisão do PR, envia os pedidos de alteração e confere depois se foram atendidos. | Aba Patrulha |
| 🛠️ [**Arsenal**](#arsenal) | Chama a IA pelo Cursor CLI ou Antigravity CLI para disparar alvos, revisar PRs, explicar falhas de teste e analisar bugs. | Ações com IA nas abas |
| 🏰 [**Base**](#base) | O painel lateral e os componentes visuais que os mecanismos compartilham. | Lateral da IDE |
| 📦 [**Caixa-Preta**](#caixa-preta) | Reporta bugs do próprio plugin como issue no GitHub: texto livre, onde foi o bug, erros do log e, se você quiser, print e GIF. E confere se a IDE tem o que cada mecanismo precisa. | Topo do painel e menu Help |

---

<a id="radar"></a>

## 📡 Radar

O Radar cuida dos alvos de ponta a ponta: no editor, onde você comenta o código, e na aba Radar,
onde acompanha a revisão aberta e o histórico.

### No editor: comentários inline, como num pull request

- 📌 **Acompanham o código:** os comentários se movem quando linhas são inseridas ou removidas.
- 🎯 **Arquivo ou pacote inteiro:** para pedidos que não cabem numa linha, como *"separe este pacote
  em dois"*, clique com o botão direito num pacote ou arquivo (na árvore do projeto, na aba do editor
  ou na aba Sentinela) e use **Marcar como Alvo**. O alvo de arquivo aparece no topo do editor, e a
  árvore do projeto mostra quantos alvos cada pacote e arquivo tem.
- 🌐 **Projeto inteiro:** para o que fala do projeto como um todo (*"padronize o tratamento de erro em
  todo o módulo de pagamentos"*), use **Comentário Geral** na barra da aba Radar, sem precisar de arquivo
  aberto. Ele aparece no topo da lista, no nó **Projeto**, e no `.md` numa seção **Geral**, no início, que
  a IA lê antes dos comentários por arquivo e que a deixa decidir onde mexer. Funciona como os outros alvos
  (editar, cancelar, resolver, *Discutir antes de alterar*) e é gravado com qualquer salvamento. Durante a
  revisão de um PR o botão fica desligado, porque os comentários vão para o PR.
- ✅ **Verificar Regras de IA:** a IA, só lendo, confere se as alterações do projeto (as não commitadas e os
  commits da branch além da base) cumprem as regras de IA dele: `CLAUDE.md`, `AGENTS.md`, `.cursorrules`,
  `.claude/rules/*`, `.cursor/rules/*` e `.github/copilot-instructions.md`, na raiz e nas subpastas, detectados
  sozinhos. Sem nenhum desses arquivos, o botão avisa e não chama a IA. O resultado abre num diálogo com a regra,
  o arquivo, a linha e o trecho de cada violação, e você marca no Radar só as que quiser (**Enviar ao Radar**);
  as que já estão lá aparecem como *já no Radar* e não duplicam. As regras que a IA não consegue conferir lendo o
  código (como "responda em português") ficam numa lista **Não verificável**, no fim. Rodar de novo substitui o
  resultado, e **Última Verificação das Regras de IA** reabre o último. Os alvos marcados levam a origem
  *Regras de IA* no `.md`.
- 🚦 **Dois modos por comentário:** *Aplicar direto* para o que é óbvio; *Discutir antes de alterar*
  para a IA ler o código e perguntar antes de mexer, com as opções, o que muda e o risco de cada uma.
- 💬 **As perguntas aparecem no Radar, sem `.md` para você ler:** ao disparar um alvo de *Discutir antes de alterar*
  com a IA pronta, ela roda só lendo e devolve as perguntas, que aparecem no topo da aba Radar, um bloco por
  comentário, com as opções (a recomendada marcada) e um campo de texto livre. Pergunta sem resposta usa a recomendação
  da IA. **Enviar respostas** faz a IA aplicar o que foi decidido, e ela pode, uma única vez, fazer mais perguntas se
  ainda tiver uma dúvida que muda o resultado. Depois disso ela aplica de qualquer jeito, assume a melhor opção onde
  restar dúvida e lista as suposições no resumo. Os alvos de *Aplicar direto* seguem o fluxo de sempre, e **Cancelar
  discussão** deixa os comentários como estavam. Sem a IA configurada, o pedido continua sendo copiado para você colar na sua.
- ✏️ **Ciclo completo:** edite, cancele com desfazer e acompanhe o status de cada alvo.
- 👁️ **Interceptados à vista ou não:** o **Ocultar** do cartão tira do editor um alvo já interceptado, e
  **Mostrar no Editor**, na aba Radar, traz de volta, inclusive os de revisões encerradas.
- 💾 **Nada se perde:** rascunhos ficam guardados mesmo se você fechar a IDE antes de salvar.

### Na aba Radar: a revisão aberta e o histórico

<p align="center">
  <img src="images/radar.png" width="820" alt="Aba Radar com a revisão aberta, uma revisão no histórico e o histórico consolidado">
</p>

A árvore mostra **revisão > arquivo ou pacote > comentário**. Um clique leva ao código, o duplo clique
foca o editor e o botão direito edita, cancela ou desfaz o cancelamento. O cabeçalho resume a revisão
aberta com um indicador por status e a barra de proporção entre eles.

| Etiqueta | Significado |
| --- | --- |
| 🔵 **Não disparado** | Comentário novo ou editado que ainda não foi para o `.md`. |
| 🟡 **Na mira** | Está no `.md`, esperando a IA. |
| 🟢 **Interceptado** | A IA marcou `[x]`: resolvido. |
| 🔴 **Cancelado no próximo disparo** | Você removeu; sai do `.md` no próximo salvamento. |
| 🟣 **Discutir antes** | A IA precisa discutir com você antes de alterar. |

### 🗄️ Histórico: consolidar e limpar

Cada revisão encerrada continua em `ironhawk/iron-dome/` como um `ordem-interceptacao-<id>.md`, e com o tempo a
lista cresce. Dois botões na barra da aba Radar resolvem isso:

| Botão | O que faz |
| --- | --- |
| 🔀 **Consolidar Histórico** | Junta todas as revisões encerradas em **`ironhawk/iron-dome/historico-interceptacoes.md`** e apaga os `.md` avulsos. Nada se perde: cada revisão vira uma seção, com os trechos de código e os comentários. Consolidar de novo acrescenta as novas revisões ao mesmo arquivo. |
| 🧹 **Limpar Histórico** | Apaga os `.md` das revisões encerradas e o histórico consolidado. Antes, mostra quantos comentários vão sair e avisa se algum ainda está pendente; a mesma janela oferece **Consolidar em Vez Disso**. |

- 🛡️ **A revisão aberta nunca entra:** só as encerradas são consolidadas ou apagadas.
- ♻️ **Recuperável:** a exclusão passa pela IDE, então os arquivos podem ser recuperados pelo **Local History**.
- 📚 **Aparece na árvore:** o nó **Histórico consolidado** mostra quantas revisões e comentários ele
  guarda e expande para cada revisão; duplo clique abre o arquivo.
- 🔢 **Sem números repetidos:** uma revisão nova não reaproveita um número que já está no consolidado,
  nem depois de clonar o projeto.

<details>
<summary><b>👀 Ver um exemplo do histórico consolidado</b></summary>

O histórico guarda o conteúdo das revisões sem as instruções para a IA, com um aviso de que não é uma
ordem de interceptação e a contagem de pendentes refeita a partir dos `[x]`.

````markdown
# Histórico de interceptações

> **Arquivo de histórico, não é uma ordem de interceptação.** Reúne as revisões encerradas que o Radar
> consolidou. IA: não aplique, não discuta e não marque os comentários daqui.

Consolidado em 01/11/2026 15:34:51. 2 revisões, 3 comentários.

| Revisão | Criada em | Comentários | Interceptados | Pendentes |
| --- | --- | ---: | ---: | ---: |
| ordem-interceptacao-2 | 01/11/2026 15:34:51 | 1 | 1 | 0 |
| ordem-interceptacao-1 | 01/11/2026 15:34:50 | 2 | 2 | 0 |

---

# Revisão: ordem-interceptacao-2

Criada em 01/11/2026 15:34:51. Atualizada em 01/11/2026 15:34:51.

Pendentes: 0 de 1.

## `src/Frete.kt`

### [x] C1 — arquivo inteiro
...
````

</details>

---

<a id="centro-de-comando"></a>

## 🎯 Centro de Comando

Ao salvar, cada comentário vira uma ordem de interceptação clara, com o modo e o código citado. O
arquivo traz no topo as regras de engajamento para a IA, então funciona com qualquer assistente, sem
configuração extra.

| Ação | Atalho | O que faz |
| --- | --- | --- |
| **Disparar Este Arquivo** | botão na barra do editor | Grava no `.md` só os comentários do arquivo aberto. |
| **Disparar Todos** | <kbd>Ctrl</kbd>+<kbd>S</kbd> ou a barra da aba Radar | Salva tudo e grava os comentários de todos os arquivos. |
| **Disparar Selecionados** | barra e botão direito da aba Radar | Só os alvos selecionados: um comentário, vários (<kbd>Ctrl</kbd>+clique), um arquivo ou a revisão. Grava os arquivos deles no `.md` e pede à IA que execute só esses alvos; sem a IA configurada, copia o pedido para você colar na sua. |
| **Disparar** | botão no cartão do alvo, no editor | O mesmo, só para aquele alvo. |

- 🤝 **Sem fogo amigo:** a IA é orientada a analisar os comentários em conjunto e a perguntar antes de
  aplicar comentários que se contradizem ou se cruzam.
- 🔁 **Volta automática:** quando a IA marca `[x]`, o plugin percebe a mudança no `.md` e mostra o
  comentário como resolvido no editor. Quando todos ficam resolvidos, a revisão se encerra e o próximo
  comentário abre uma nova.

<details>
<summary><b>👀 Ver um exemplo do <code>.md</code> gerado</b></summary>

````markdown
# Revisão: ordem-interceptacao-1

Criada em 31/10/2026 10:05:12. Atualizada em 31/10/2026 10:12:40.

> **Instruções para a IA** (valem para qualquer assistente ou modelo):
> ...

Pendentes: 2 de 2.

## `src/main/kotlin/loja/Pedidos.kt`

### [ ] C1 — linha 42

**Modo:** aplicar direto

```kotlin
pagamento.cobrar(total)
```

**Comentário:**

Valide o estoque antes de cobrar o pedido.

### [ ] C2 — linhas 58–63

**Modo:** discutir antes de alterar

> **IA: discuta antes de alterar.** Não altere nenhum arquivo por causa deste comentário. ...

```kotlin
fun calcularFrete(pedido: Pedido): BigDecimal {
    ...
}
```

**Comentário:**

Esse cálculo deveria considerar o peso dos itens?
````

</details>

---

<a id="raio-de-impacto"></a>

## 💥 Raio de Impacto

Veja a classe e a vizinhança dela como num diagrama de classes. Abra uma classe Java ou Kotlin no editor e use
**Mirar Classe Atual** na barra da aba.

### O diagrama de classes

Cada classe é uma caixa em três partes, como na UML: o **nome** (com o estereótipo `«interface»`, `«enumeration»` ou
`«annotation»` quando for o caso, e o nome em itálico nas abstratas), os **atributos** e as **operações**. Os membros
levam a visibilidade (`+` público, `-` privado, `#` protegido, `~` do pacote), e as classes grandes mostram os primeiros
8 de cada parte, com um "… mais N" no fim. A classe analisada tem a borda destacada.

| Seta | Significa |
| --- | --- |
| ──▷ Linha cheia, triângulo vazado | **Herança**: a classe herda da outra. |
| - - ▷ Linha tracejada, triângulo vazado | **Realização**: a classe implementa a interface. |
| ──> Linha cheia, ponta aberta | **Associação**: a classe tem um atributo do tipo da outra. |
| - - > Linha tracejada, ponta aberta | **Uso**: chama, instancia ou usa membros estáticos da outra. |

As classes pais ficam acima das filhas, e as dependências descem. O raio é de 1 nível, nas duas direções: quem depende
da classe e de quem ela depende.

- 🖱️ **Clique** seleciona a caixa (e a linha dela na tabela), **dois cliques** abrem o código e <kbd>Ctrl</kbd>+clique
  mira a classe clicada.
- 🔎 **Busca:** <kbd>/</kbd> foca a busca, <kbd>Enter</kbd> passa pelas classes encontradas.
- 🧭 **Navegação:** arraste para mover, a roda aproxima e afasta, e os botões do canto aproximam, afastam, enquadram o
  diagrama e centralizam na classe analisada.

### Classes no raio

A tabela abaixo do diagrama lista cada classe com o pacote, as **aferentes** (quantas classes dependem dela: o que
pode quebrar se você mudá-la) e as **eferentes** (de quantas ela depende: um número alto indica uma classe que faz
coisas demais). Clicar numa linha seleciona a caixa no diagrama, e dois cliques abrem o código.

---

<a id="sentinela"></a>

## 👁️ Sentinela

A aba Sentinela vigia o perímetro: os arquivos ainda não commitados, em grupos que você expande ou recolhe.

- 🗂️ **Agrupar por:** raiz de código e pacote (como `src/main/kotlin` > `app.pedidos`), só pacote ou
  tipo de alteração. A escolha fica salva, e com mais de 20 arquivos os grupos de arquivos chegam
  recolhidos, para a lista não crescer demais.
- 🔎 **Busca e filtros:** a busca procura no nome, na pasta e no pacote; o botão **Filtrar** mostra só
  alguns tipos de alteração ou **Só com Alvos**. O cabeçalho avisa quantos arquivos o filtro está mostrando.
- 🏷️ **Etiquetas por arquivo:** o tipo de alteração (modificado, novo, movido, fora do git) e quantos
  alvos do Radar cada arquivo tem.
- 🔍 **Inspecionar Diff** abre o diff do arquivo, e **Abrir Arquivo** leva ao editor.
- 🎯 **Marcar como Alvo** direto da lista, para comentar um arquivo ou pacote inteiro.
- 🔄 **Sempre em dia:** a lista acompanha commits, edições e reverts sem precisar atualizar.

---

<a id="escudo"></a>

## 🛡️ Escudo

**Executar Todos os Testes** compila o projeto e roda todos os testes JUnit pela própria IDE (a
execução também aparece na janela Run, com o console completo). O resultado chega ao vivo.

> **Em definição:** o padrão para ligar os testes aos critérios de aceite (catálogo em YAML, tags `criterio:` e `nivel:`
> e a visão por critério) está proposto em [Escudo: critérios de aceite](escudo-criterios.md).

<p align="center">
  <img src="images/escudo.png" width="820" alt="Aba Escudo com os testes agrupados por chave e os nomes formatados">
</p>

### Agrupados pela chave do `@DisplayName`

A convenção é começar o `@DisplayName` da classe de teste com uma chave entre colchetes. As classes com
a mesma chave ficam juntas, em ordem alfabética, com as classes `@Nested` dentro da classe que as
contém e cada teste com o próprio `@DisplayName`. O nome da classe ou do método aparece em cinza ao
lado, para você saber onde está.

```java
@DisplayName("[Frete] Cálculo do frete")
class FreteServiceTest { ... }
```

- 🎨 **Chaves coloridas:** cada chave vira uma etiqueta com cor própria, sempre a mesma para a mesma chave.
- 🏷️ **Agrupar por `@Tag`:** em **Agrupar Por**, na barra, a árvore pode vir por `@Tag` do JUnit (Tag → Chave →
  Classe → testes) ou pela chave do `@DisplayName`. Sem escolha, ela vem por tag quando os testes têm `@Tag`.
  Vale a tag do método; sem ela, a da classe (e da classe de fora, nas `@Nested`). `@Tags` e anotações próprias
  marcadas com `@Tag`, como um `@Lento`, também contam. Um teste com duas tags aparece nas duas, e os sem tag ficam
  em **Sem tag**. No agrupamento por chave, cada teste mostra as tags dele.
- ⚠️ **Convenção cobrada:** classes sem `@DisplayName` ou sem chave vão para o grupo **Sem chave**, o
  topo da aba avisa quantas estão fora da convenção e os detalhes sugerem o `@DisplayName` de cada uma.
- 🔧 **Deixar no Padrão:** marca as classes fora da convenção (as da seleção ou, sem nenhuma nela, todas) como
  alvos na revisão aberta do Radar, na linha do `@DisplayName` ou da declaração da classe. Esses alvos vêm
  assinalados como do Escudo: ícone do Escudo e etiqueta **Escudo** no editor, **Marcado pelo Escudo** na aba
  Radar e `**Origem:** Escudo` no `.md`, para a IA saber que não foi o revisor que pediu. O pedido traz as chaves
  que o projeto já usa, para a IA reaproveitar, e manda alterar só o `@DisplayName`. Mesmo no modo revisão de um
  PR, eles vão para o Radar, não para o PR, e uma classe que já tem alvo aberto do Escudo não é marcada de novo.

### Nomes dos testes formatados

O texto do `@DisplayName` de cada teste ganha destaque para você ler o cenário de relance:

| Escrito no `@DisplayName` | Como aparece |
| --- | --- |
| `Dado que…`, `quando…`, `então…` (ou `given`, `when`, `then`) | Etiquetas **DADO**, **QUANDO** e **ENTÃO** separando as etapas do cenário |
| `deve…`, `should…` / `não deve…`, `should not…` | Verbo da expectativa em negrito; o negativo em laranja |
| `` `código` `` e `"strings"` | Fundo lilás, como trecho de código |
| `IllegalStateException`, `…Error` | Fundo vermelho |
| Números e valores: `200`, `R$ 200`, `15%` | Azul em negrito |
| `soma 2 e 3 -> 5` (`->`, `=>`, `→`) | Seta destacada e o resultado esperado em verde |
| `[1] cep=01001-000, valor=10.0` (testes parametrizados) | Etiqueta **#1** e cada parâmetro com o valor destacado |

> [!TIP]
> "Dado" sozinho também quer dizer informação ("valida os dados do cliente"), então ele só vira etapa
> no início do nome ou na forma "dado que". "Quando" e "então" valem em qualquer posição.

<p align="center">
  <img src="images/escudo-detalhes.png" width="820" alt="Detalhes de um teste com a seção Cenário e de uma execução parametrizada com os parâmetros">
</p>

O painel de detalhes repete a formatação no título e, quando o nome tem essa estrutura, mostra o
**Cenário** em etapas (Dado, Quando, Então) e os **Parâmetros desta execução**, com nome e valor.

### Resultado e falhas

- ✅ **Passou ou não:** cada chave e cada classe mostram uma minibarra com a proporção de testes que
  passaram, falharam ou foram ignorados, a contagem (como `2/3`) e quanto tempo levaram. O cabeçalho
  resume se o escudo está **íntegro** ou **rompido**.
- 🔍 **Falhas em destaque:** as classes com falha se abrem sozinhas, o teste que falhou mostra a
  primeira linha do erro logo abaixo do nome, **Mostrar Só as Falhas** esconde o resto, e o painel de
  detalhes traz o motivo e a pilha do erro. Duplo clique abre o teste no editor.

> [!NOTE]
> Os testes rodam pelo runner JUnit da própria IntelliJ, numa configuração com todos os testes do
> projeto, e não pelo Gradle. Num projeto em que os testes só funcionam via Gradle, o resultado pode
> ser diferente do `gradlew test`.

---

<a id="patrulha"></a>

## 🛰️ Patrulha

A aba Patrulha revisa os pull requests do GitHub na IDE: no modo revisão do PR, os comentários que você faz
no editor vão para o PR, e não para a IA; você envia tudo como uma revisão e depois confere se cada pedido
foi atendido.

1. **Escolha o PR.** A aba lista os PRs abertos do remote do GitHub, com o autor, as branches e quantos
   pedidos já foram atendidos. Em cada PR, **Arquivos alterados** mostra o que mudou agrupado por pasta, com as
   pastas de um filho só juntas numa linha (como `src/main/kotlin/com/mio`): cada pasta soma os arquivos, as
   linhas `+`/`−` e os comentários, e cada arquivo vem com a cor e a etiqueta do status (novo, alterado,
   removido, renomeado). Pastas fechadas continuam fechadas quando a lista atualiza; duplo clique ou **Ver
   Alterações** abre o diff do PR numa aba, comparando com a mesma base do GitHub, e as setas do diff
   passam pelos demais arquivos. A conta é a que você já usa no IntelliJ (**Settings > Version Control >
   GitHub**). Se a organização bloquear o login pelo navegador (restrição a OAuth Apps), use **Entrar com
   Token** na aba, com um token pessoal classic com escopo `repo`.
2. **Revise.** **Revisar Este PR** traz o código do PR para a branch local `pr-<número>` e entra no modo
   revisão do PR; se você já está na `pr-<número>`, no head do PR, só entra no modo revisão. Outra branch no mesmo
   commit, como a do autor do PR, não conta: ela pode ter alterações não commitadas. O que não estiver commitado (fora de `ironhawk/` e
   `.idea/`) vai para o stash, com a lista dos arquivos e a sua aprovação antes. Não há botão de checkout
   à parte: se o PR receber commits novos, o mesmo botão vira **Atualizar ou Encerrar Revisão**.
   **Encerrar Revisão** oferece voltar para a branch de antes e devolver o stash. O modo revisão só vale na
   branch do PR: trocar de branch por fora da Patrulha (pelo Git da IDE ou pelo terminal) encerra a revisão na
   hora, e o aviso oferece **Voltar ao PR #n** e, se você voltou para a branch de antes, **Devolver as
   Alterações do Stash**. No modo revisão, o formulário de comentário fica roxo, com o título *Comentário para o PR #n*
   e o botão **Adicionar ao PR**, e os comentários ganham a etiqueta *Pedido para o PR #n · Não enviado*,
   fora do `.md` da IA. Com o checkout no head do PR, o próprio editor mostra o que o PR mudou, como o
   diff do GitHub: linhas novas em verde e alteradas em azul (fundo e barra na margem), um triângulo vermelho
   onde linhas saíram e as mesmas cores na barra de rolagem, para achar os trechos e comentar direto no
   arquivo, sem abrir o diff. O destaque some ao encerrar a revisão. **Sugerir código** abre um editor de verdade sobre uma cópia do arquivo, com o
   trecho comentado destacado e editável e o resto do arquivo recolhido e só de leitura: Ctrl+Espaço,
   imports, navegação e erros funcionam como no editor, sem alterar o arquivo. Escreva como gostaria que
   ficasse: nas linhas do diff, vira uma sugestão do GitHub que o autor aplica com um clique
   (*Commit suggestion*); fora do diff, vai como código de exemplo.
3. **Envie.** **Enviar ao GitHub** mostra onde cada comentário vai entrar. Por padrão, eles vão como
   **rascunho no GitHub** (a revisão pendente, que só você vê): enviar de novo acrescenta ao mesmo rascunho,
   e **Editar**, **Excluir** e **Excluir Todos** valem lá também. Quando estiver pronto, **Concluir Revisão**
   escolhe o veredito (**Aprovar**, **Solicitar alterações** ou **Só comentar**), e o autor passa a ver.
   Desmarcando o rascunho, o envio já publica. Linhas que o PR altera viram comentários de linha; linhas fora do diff e arquivos inteiros,
   comentários do arquivo; pacotes e arquivos fora do PR entram no texto geral. Se o rascunho for publicado
   ou descartado pelo próprio GitHub, a Patrulha percebe na sincronização e atualiza o `.md`.
   **Concluir Revisão**, na barra da aba, dá o veredito a qualquer hora, com ou sem comentários: já vem em
   *Solicitar alterações* quando há comentários e em *Aprovar* quando não há. O texto é opcional para aprovar e
   obrigatório para solicitar alterações sem comentários, e os comentários não enviados e o rascunho vão junto,
   na mesma revisão. No próprio PR, o GitHub só deixa comentar.
4. **Acompanhe no código.** Com a revisão ligada, os pedidos já enviados aparecem no editor como cartões
   recolhidos, onde o trecho está agora, com o status (*Enviado*, *Atendido*, *Sem alteração*). O
   cabeçalho expande, **Ocultar** tira o cartão e **Editar** troca o texto e o código sugerido também no
   GitHub. Na aba, **Mostrar/Ocultar no Editor** vale para o histórico do PR inteiro ou para um pedido, e
   **Editar** serve para rascunhos e pedidos enviados.
   Os comentários de outras pessoas também vão para o código: cada conversa de linha aparece como cartão
   **só de leitura** na linha comentada, com as respostas abaixo e o status (*Aberta*, *Resolvida*,
   *Desatualizada*). As do arquivo inteiro ficam no topo. O cartão não tem Editar, só expandir e **Ocultar**;
   para responder, use **Responder** na aba. **Mostrar/Ocultar no Editor** em *Conversas no PR* alterna todas,
   e numa conversa, só ela.
   Os cartões, pedidos e conversas, só aparecem no editor durante a revisão do PR, depois de **Revisar Este
   PR**: fora dela, as linhas não batem com as do GitHub. Encerrar a revisão tira os cartões, e fora dela o
   **Mostrar no Editor** fica desabilitado.
   Durante a revisão, no próprio editor (na barra do canto e no botão direito): **Minimizar Comentários do PR**
   recolhe todos numa barra de uma linha, inclusive os seus não enviados, e vira **Expandir** quando já estão
   recolhidos; o cabeçalho de cada cartão abre e fecha só ele. **Ocultar Comentários do PR** tira todos do
   editor sem apagar nenhum e fica pressionado enquanto eles aparecem; comentar de novo traz os seus de volta.
   **Mostrar Comentários Ocultados do PR** traz os que você ocultou um a um pelo **Ocultar** do cartão.
   Se o revisor **editar ou apagar** o comentário direto no GitHub, a Patrulha sincroniza ao atualizar a
   aba, ao iniciar a revisão e em **Verificar Pedidos**: o `.md` passa a ter o texto do GitHub (*Editado no
   GitHub*), e os apagados ficam riscados (*Apagado no GitHub*), fora das contas e sem Editar.
   **Excluir** apaga o comentário selecionado: o não enviado, o rascunho (no GitHub também) ou o publicado
   (no GitHub só se você escolher). **Excluir Todos** faz isso com o PR inteiro: os não enviados, o rascunho
   descartado no GitHub e os publicados, que só saem do GitHub se você marcar.
5. **Verifique.** Os pedidos ficam em `ironhawk/iron-dome/pull-requests/pr-<número>.md`. **Verificar Pedidos**
   confere cada um de dois jeitos:

| Verificação | Como funciona |
| --- | --- |
| 🧾 **Código** | Compara o trecho comentado com o arquivo no head atual do PR: *Como sugerido* (o head tem o código sugerido), *Alterado*, *Sem alteração*, *Só mudou de lugar*, *Arquivo removido* ou *Sem commits novos*. |
| 💬 **Conversa** | Lê a conversa do comentário no PR: *Resolvida*, *Respondida* (com a última resposta do autor) ou *Aberta*. |

Um pedido conta como atendido quando o código mudou ou a conversa foi resolvida; e como pendente quando
nada disso aconteceu depois de commits novos.

   **Minhas Instruções e Regras para a IA**, na barra da aba, guarda a sua linha de avaliação de PR, nas suas palavras:
   o que bloqueia um PR para você, o que é só sugestão, o que ignorar, os padrões que você cobra, quando aceitar
   a explicação do dev e o tom. A IA segue essas instruções acima do padrão, na **Revisão com IA** e nas
   respostas automáticas. Elas ficam nas preferências da IDE e valem para todos os projetos.

   No mesmo diálogo, **Regras deste projeto** vincula arquivos `.md` com as regras do time (como `AGENTS.md`,
   `CLAUDE.md` ou `.github/copilot-instructions.md`) ou pastas inteiras (como `.cursor/rules`, pelos `.md` e
   `.mdc` de dentro); **Detectar** acha os que existem nos lugares de costume. O conteúdo é lido a cada revisão e
   vai para a IA, que avalia o PR também por essas regras e diz qual regra e de qual arquivo foi descumprida.
   Cada arquivo entra com até 12 mil caracteres, e todos juntos com até 40 mil. O vínculo vale só para este projeto.
6. **Deixe a IA conversar.** **Ligar Respostas da IA**, na barra da aba, liga o modo automático no PR
   selecionado (com a IA configurada no Arsenal). A cada 2 minutos, enquanto a IDE estiver aberta, a
   Patrulha confere as conversas de linha que você abriu no PR. Quando alguém responde, a IA lê a conversa
   inteira, o pedido original e o código no head do PR, e decide:
   - **Responder:** publica a resposta em seu nome e espera o dev de novo (*IA aguardando o dev*).
   - **Concluir:** quando o dev corrigiu, explicou bem ou os dois combinaram um próximo passo, publica a
     mensagem final e resolve a conversa no GitHub (*IA concluiu*).
   - **Passar para você:** quando a conversa pede uma decisão sua (regra de negócio, escopo, divergência
     ou tom tenso), não publica nada e avisa (*IA pede você*).

   As respostas são publicadas direto, sem perguntar, e terminam com uma nota dizendo que foram da IA.
   Cada conversa tem no máximo 5 rodadas da IA; passou disso, ela para e avisa (*IA no limite de rodadas*).
   Responder você mesmo não atrapalha: a IA só entra quando a última palavra é de outra pessoa. O modo
   desliga sozinho quando o PR é fechado, e **Desligar Respostas da IA** para na hora.

   O nó **Respostas da IA**, no PR, mostra o que o modo está fazendo: *Trabalhando* com o passo de agora
   (*Lendo as suas conversas no GitHub…*, *IA avaliando a resposta de @dev em `Frete.kt`, linha 30…*,
   *Publicando a resposta…*), *Ligadas* com a hora da última conferência e da próxima, ou *Parado* com o
   motivo (IA não configurada, ocupada com outra tarefa, sem conta do GitHub, erro do GitHub). As etiquetas
   contam as suas conversas abertas, as que aguardam o dev e as que têm resposta nova para a IA. Abaixo fica
   o histórico, com a hora e o resultado de cada conferência e de cada conversa; duplo clique abre a
   conversa no GitHub. **Conferir Respostas Agora** não espera os 2 minutos.

> [!NOTE]
> A Patrulha usa os plugins Git e GitHub que vêm no IntelliJ IDEA. Sem eles, a aba não aparece, e
> **Verificar Compatibilidade** mostra o motivo.

---

<a id="base"></a>

## 🏰 Base

O painel lateral **Iron Dome IA** reúne as abas Radar, Sentinela, Raio de Impacto e Escudo. Elas
compartilham os mesmos componentes visuais, para tudo ter a mesma cara:

- 📊 **Cabeçalho de resumo:** o estado em destaque (como *Céu limpo*, *Perímetro limpo* ou *Escudo
  íntegro*), indicadores coloridos com a contagem de cada status, uma barra com a proporção entre eles
  e, quando há algo a corrigir, um aviso.
- 🏷️ **Etiquetas arredondadas** nas árvores, nas mesmas cores dos indicadores, para você bater o olho e
  saber o que pede atenção.
- 🧰 **Barra flutuante do editor**, no canto superior direito: **Inspecionar Diff** quando o arquivo tem
  alterações não commitadas, e **Disparar Este Arquivo** e **Disparar Todos** enquanto há alvos não disparados.

---

<a id="arsenal"></a>

## 🛠️ Arsenal (IA)

O Arsenal chama a IA direto da IDE, pelo CLI do **Cursor** (`agent`) ou do **Antigravity** (`agy`), rodando
na sua máquina sobre o projeto aberto. Enquanto ela trabalha, a aba que pediu (Radar, Patrulha ou Escudo) e a
aba Arsenal mostram uma barra com o passo atual (como *Lendo src/Frete.kt*), o tempo, a última linha que a
IA escreveu e **Cancelar**; o ícone da aba e o do painel viram o ícone de IA, para dar para ver mesmo com o
painel fechado. Execuções sem aba própria, como as da Caixa-Preta, aparecem em todas as abas. Cada execução fica
registrada em `ironhawk/iron-dome/ia/` com o pedido, os passos, os arquivos alterados e a resposta (**Ver Resposta**).

A aba Arsenal tem o estado da IA fixo no topo ("IA pronta…", "IA não configurada", "Teste a integração") e quatro abas
internas: **IA** (configuração automática, CLI, executável, modelo e teste; tempo máximo e argumentos extras ficam
em **Opções avançadas**, recolhidas), **Execução** (onde rodar, devcontainer e proteções), **Recursos** (o que a IA
libera e o Spec Kit) e **Execuções** (as recentes). Uma aba com pendência ganha um ponto amarelo (falta fazer) ou
vermelho (quebrado) no título, e o link no topo ("Resolver: …") leva a ela. A aba abre onde há algo a resolver; com
tudo pronto, abre em **Execuções**.

Na aba **Recursos**, **Skills extras** lista as skills achadas no projeto (`.claude/skills`, `.cursor/skills`,
`.agents/skills`, `.codex/skills`) e na sua pasta pessoal, com nome e descrição lidos do `SKILL.md`; as do próprio
Spec Kit ficam de fora. Se uma não aparecer, **Adicionar Pasta de Skills…** aponta a pasta onde ela está (uma pasta
com várias skills, ou a de uma só). Cada skill marcada vira um botão avulso no **Lançador** do Ataque, ao lado das
etapas do Spec Kit, e não entra no avanço da sequência. O botão pede uma instrução opcional e roda a skill com o
CLI, o host e as proteções do Arsenal, passando a feature escolhida; sem a skill na máquina, o botão fica desligado
com o motivo. A escolha vale só para você, nesta IDE, e nada vai para o projeto. A skill roda como uma execução da
IA comum (aparece nas Execuções do Arsenal e tem o **Desfazer Alterações da IA**), não como missão com Telemetria.

No Windows, o CLI também pode estar no **WSL**: em **Onde está o CLI**, escolha **No WSL** e a distribuição (a lista
vem do `wsl.exe`, sem as internas do Docker Desktop). O plugin roda o CLI dentro dela pelo shell de login, que acha o
`agent` ou o `agy` em `~/.local/bin` (ou use o caminho do Linux em **Executável**), converte a pasta do projeto para
`/mnt/c/...` e repassa a chave de API do Cursor pelo ambiente. Vale para o teste, para a aba e para o terminal, e a
configuração automática procura também nas distribuições do WSL quando não acha o CLI no Windows.

Em **Onde rodar a IA** (na aba Arsenal ou em Settings), escolha **Na aba, no devcontainer**, **No terminal
da IDE** ou **Perguntar a cada vez**. Na aba, a IA sempre roda no devcontainer do projeto
(`.devcontainer/tomahawk/devcontainer.json`, o mesmo das missões do Tomahawk), que só enxerga a pasta do projeto: o
plugin sobe o container (ou reaproveita o que está de pé), confere o isolamento e roda o Cursor, o Claude Code ou o Codex lá
dentro. Sem o devcontainer, a IA não roda na aba, e o aviso oferece criá-lo; o Antigravity não está no container e
só roda no terminal. No terminal, cada pedido abre uma aba do terminal com o CLI à vista: o Cursor
abre interativo, para acompanhar e conversar com a IA (vale o login do `agent login`; a chave de API não vai para o
terminal, para não ficar no histórico do shell), e o Antigravity roda o pedido e mostra a resposta. A IA grava a
resposta final em `ironhawk/iron-dome/ia/respostas/`, e o plugin segue dali como na aba: os rascunhos do Revisar com IA, a
análise no `bug.md`, os avisos. Enquanto isso, a barra da aba mostra **Mostrar Terminal** e **Parar de Esperar**.
Sem o plugin de terminal da IDE, a IA roda na aba.

Os dois caminhos têm teste, no Windows ou no WSL, como estiver configurado: **Salvar e Testar** confere a execução
em segundo plano (e habilita os botões de IA), e **Testar no Terminal** (na aba Arsenal, ou **Salvar e Testar
no Terminal** em Settings) abre a aba *IA: Teste* do terminal, pede à IA que grave OK num arquivo e mostra quanto
tempo ela levou e em qual shell rodou. Se a resposta não chega, a aba do terminal mostra o que deu errado: o
comando, o login ou o PATH.

| Onde | Ação | A IA pode alterar arquivos? |
| --- | --- | --- |
| 📡 Radar e barra do editor | **Disparar Todos com IA**: dispara os alvos e a IA executa a ordem de interceptação, marcando `[x]` nos atendidos. **Disparar Selecionados** (aba Radar) e **Disparar** (no cartão do alvo, no editor): a IA executa só os alvos escolhidos | Sim |
| 🛰️ Patrulha | **Revisar com IA** (no modo revisão): a IA lê os arquivos alterados e propõe comentários, que viram rascunhos para você revisar antes de enviar | Não |
| 🛡️ Escudo | **Explicar Falha com IA**: causa, onde e a correção proposta; a resposta oferece **Corrigir com IA** | Não (Corrigir: sim) |
| 📦 Caixa-Preta | **Analisar com IA**, no bug gerado e em Ver Logs de Erro: causa provável e correção; no bug, a análise entra no fim do `bug.md` | Não |

Revisar não é alterar: **enquanto um PR está em revisão na Patrulha, a IA não altera o código**, porque o checkout
local é o do PR. Os disparos do Radar e o Corrigir com IA ficam desligados até você sair da revisão. E, em qualquer
pedido que altera código, a IA é instruída a não seguir os comentários de pull request (`ironhawk/iron-dome/pull-requests/`
e as revisões de PR em `ironhawk/iron-dome/ia/`); nos pedidos só de leitura, também no terminal, ela é instruída a não
alterar nada.

**Contenção da IA**, sempre ligada, para a IA não danificar a máquina nem o projeto:

| Proteção | Como funciona |
| --- | --- |
| ⏪ Ponto de restauração | Antes de cada tarefa que altera arquivos, o plugin tira uma foto dos arquivos do projeto no git, inclusive o que não foi commitado nem adicionado, numa referência própria (`refs/iron-dome/antes-da-ia/`, as 10 últimas), sem mexer no seu índice, na branch nem nos stashes. O aviso do fim traz **Desfazer Alterações da IA**: o que a IA alterou ou apagou volta, o que ela criou sai, e os registros em `ironhawk/iron-dome/ia/` ficam. Fora de um repositório git, o ponto é o do Local History da IDE, que só volta o que a IDE já tinha lido. |
| 🔒 Ambiente contido | Na aba, a IA roda sem `git push` (qualquer push dela falha; buscar do remoto continua), sem o git pedir senha e sem os tokens, senhas e chaves do ambiente que o CLI não usa, como `GITHUB_TOKEN` ou `AWS_SECRET_ACCESS_KEY`. |
| 🚫 Bloqueio no CLI | O Claude Code recusa antes de rodar os comandos destrutivos, mexer no `.git` e ler ou alterar `~/.ssh` e `~/.aws`. No WSL, e fora do Windows, o Cursor roda no sandbox dele. |
| 🧯 Vigia | O plugin confere cada passo que o CLI informa e para a IA na hora se ela tentar apagar pastas (`rm -rf`, `Remove-Item -Recurse`, `rd /s`), descartar alterações ou reescrever o histórico do git (`reset --hard`, `clean -f`, `checkout --`, `rebase`, `push`), rodar como administrador, formatar disco, desligar a máquina, alterar o registro, baixar e executar scripts, ou escrever fora do projeto ou no `.git`. O aviso diz o que ela tentou. |
| 📝 Regra no pedido | Toda tarefa que altera arquivos diz à IA o que ela não pode fazer e que, se precisar, deve parar e explicar. |

No terminal da IDE a IA roda interativa e pede a sua aprovação a cada passo; o ponto de restauração vale também lá.

Na primeira vez que a aba **Arsenal** abre, a IA se configura sozinha: o plugin procura o Cursor CLI e o
Antigravity CLI instalados e testa cada um, com o modelo padrão, até um responder. Se nenhum responder, a
configuração manual abre já com o motivo de cada falha no cabeçalho. **Configurar Automaticamente** refaz essa
busca a qualquer hora, também em Settings.

Na configuração manual, na aba **Arsenal** do painel (ou em **Settings > Tools > Iron Dome IA > Arsenal (IA)**): o
CLI, o executável (vazio procura no PATH e nas pastas de instalação), o modelo, o tempo máximo e,
opcionalmente, a chave de API do Cursor (sem ela, vale o `agent login`). **Salvar e Testar** faz uma pergunta curta
à IA: **os botões de IA só se habilitam depois que o teste passa**, e trocar o CLI, o executável, o modelo ou a
chave pede um teste novo. A aba mostra o estado (*IA não configurada*, *Teste a integração*, *IA pronta*), quais
ações estão habilitadas e as execuções recentes, que abrem com duplo clique.

> [!NOTE]
> O `agy -p` do Antigravity pode travar quando chamado por outro programa
> ([issue 318](https://github.com/google-antigravity/antigravity-cli/issues/318)); o tempo máximo interrompe a execução.

---

<a id="caixa-preta"></a>

## 📦 Caixa-Preta

Achou um bug no Iron Dome IA? O botão da caixa-preta laranja no topo do painel (ou **Help > Reportar Bug do
Iron Dome IA**) abre um formulário curto: um título e um texto livre. O relatório é sobre o **plugin**, não sobre o
seu projeto, e vira uma issue pública em
[main-io-tech/ironhawk-ia](https://github.com/main-io-tech/ironhawk-ia/issues).

- ✍️ **Texto livre:** conte do seu jeito o que deu errado. Se a IA estiver pronta, ela só organiza o seu texto em
  *o que aconteceu*, *passos* e *esperado*, sem ler o código do seu projeto nem acrescentar nada. Sem a IA, ou se
  ela falhar, o relatório segue com o texto como você escreveu, marcado como não organizado, e nunca é bloqueado.
- 📸 **Print sob demanda:** **Tirar print** esconde o formulário, espera 3 s para você abrir a tela do problema e
  captura a janela da IDE. Uma miniatura mostra o que vai junto, e dá para tirar de novo ou descartar. Nenhum print
  vai sem você pedir.
- 🎥 **Gravação em GIF:** **Gravar GIF** esconde o formulário e grava a janela da IDE por até 30 s enquanto você
  reproduz o bug. **Parar Gravação**, na notificação, traz o formulário de volta.
- 👀 **Prévia antes de publicar:** a tela seguinte mostra exatamente o que será publicado, avisa que a issue é
  pública (o texto, os erros do log, que podem trazer caminhos do seu projeto, e o ambiente) e deixa tirar os erros
  do log antes de **Publicar no GitHub**.
- 🧭 **Onde foi o bug:** o relatório aponta o pacote e a pasta do código do mecanismo e, se o `idea.log` tiver erros
  que passaram pelo plugin, a **origem provável**: o primeiro ponto do plugin na pilha do erro mais recente.
- 🧾 **Ambiente:** versões do plugin, da IDE, do Java e do sistema, os plugins Java e JUnit, o controle de versão,
  a aba ativa e o arquivo aberto.
- 🐙 **Conta do GitHub:** a issue `[Bug] <mecanismo>: <título>` é criada com a conta do GitHub que você já tem no
  IntelliJ (a mesma da Patrulha, em **Settings > Version Control > GitHub**). Sem conta, o aviso leva às
  configurações; se o repositório ficar privado e a sua conta não tiver acesso, a mensagem diz que falta permissão
  no repositório. Sem os plugins Git e GitHub da IDE, o plugin abre a página de nova issue no navegador, já preenchida.
- 📎 **Anexos:** a API do GitHub não anexa imagens, então a issue sai só com texto. Depois de criá-la, o plugin abre
  a issue no navegador e a pasta com o `print.png` e o `gravacao.gif` (e o `bug.md`), e avisa para você arrastar os
  arquivos para a issue. Eles também ficam públicos.
- 🏷️ **Label:** o prefixo `[Bug]` no título é o que a GitHub Action do repositório
  (`.github/workflows/label-bug.yml`) usa para aplicar a label `bug`, já que quem não tem permissão de triagem
  não consegue definir labels pela API.

### 🩺 Verificar Compatibilidade

Nem toda IDE tem tudo o que os mecanismos precisam: o Raio de Impacto usa o plugin Java e o navegador
embutido (JCEF), e o Escudo, o plugin JUnit. Ao abrir o projeto, o Iron Dome IA confere isso e, se algum
mecanismo não vai funcionar, avisa qual e por quê, com **Ver Compatibilidade** e **Não Avisar de Novo**.

- 📜 **Iron Dome IA: Ver Logs de Erro** (menu **Help**, engrenagem do painel, abas que não abriram e
  Verificar Compatibilidade) lista os erros e avisos do plugin no `idea.log` (e no log anterior), agrupando
  as repetições, com o mecanismo, a origem no código e a pilha completa. Filtra por mecanismo e por nível e
  tem **Copiar Erro**, **Copiar Todos** (em Markdown, para colar numa conversa ou issue) e **Reportar Este
  Erro**, que abre o Reportar Bug com esse erro e o mecanismo dele.
- 🚨 Quando o plugin lança uma exceção, a janela de erros da própria IDE ganha o botão **Reportar ao Iron
  Dome IA**, que abre o Reportar Bug com o erro e a nota que você escreveu lá.
- 🩺 **Iron Dome IA: Verificar Compatibilidade** (menu **Help** ou engrenagem do painel) mostra a situação de
  cada mecanismo (OK, opcional, limitado ou indisponível), o que fazer para resolver e os erros recentes do
  plugin no `idea.log`, com **Copiar Diagnóstico**, **Abrir idea.log** e **Reportar Bug**.
- 🧯 **Uma aba com erro não derruba as outras:** se uma aba não abrir, o lugar dela mostra o erro, com
  atalhos para a verificação e para o Reportar Bug, e as demais abas e o botão de bug continuam no painel.

---

<a id="instalacao"></a>

## 📦 Instalação

1. Baixe o `.zip` do plugin (ou gere com `.\gradlew.bat buildPlugin`).
2. No IntelliJ: **Settings > Plugins > ⚙️ > Install Plugin from Disk...** e escolha o arquivo.
3. Pronto: o painel **Iron Dome IA** aparece na lateral da IDE.

> [!NOTE]
> Compatível com IntelliJ IDEA 2025.2 em diante. O Raio de Impacto usa o plugin Java, e a aba Escudo,
> o plugin JUnit; os dois já vêm no IntelliJ IDEA.

> [!WARNING]
> Vindo de uma versão anterior? O ID do plugin mudou para `com.mio.irondome`, então a IDE trata esta
> versão como outro plugin. Desinstale a versão antiga antes de instalar esta.

<a id="configure-sua-ia"></a>

## 🤖 Configure sua IA

O `.md` gerado já traz as instruções para a IA. Para que ela trate as revisões sem você precisar
pedir, adicione ao `AGENTS.md` (ou a uma rule do Cursor) do projeto revisado:

```markdown
## Revisões em `ironhawk/iron-dome/`

Quando houver arquivos em `ironhawk/iron-dome/ordem-interceptacao-*.md` com comentários pendentes (`[ ]`), trate cada
um no arquivo indicado na seção dele (código, spec, documentação ou configuração), localizando o
ponto pelo trecho citado, e marque-o como `[x]` ao terminar. Não edite o restante do `.md`.
Comentários de arquivo inteiro valem para o arquivo todo, e os de pacote inteiro, para todos os
arquivos da pasta indicada na seção.
Antes de alterar qualquer arquivo, analise os comentários pendentes em conjunto. Se algum contradizer
outro, ou se aplicar um quebrar outro, pergunte ao usuário como resolver e espere a resposta. Sem
conflitos, trate os comentários em sequência. Os de `**Modo:** aplicar direto` podem ser aplicados
sem consulta. Nos de `**Modo:** discutir antes de alterar`, siga o modo de discussão do `.md`:
escreva a análise completa (por que o código está assim, se o apontamento procede e o impacto de
manter, alterar ou remover), de 2 a 5 opções com prévia da alteração e a sua recomendação, discuta
com o usuário e só altere depois da decisão final.
O arquivo `ironhawk/iron-dome/historico-interceptacoes.md` é só histórico: não aplique nem marque nada nele.
```

> [!TIP]
> Depois disso, basta dizer à IA algo como *"aplique a revisão"*.

<a id="faq"></a>

## ❓ Perguntas frequentes

<details>
<summary><b>Com qual IA ele funciona?</b></summary>

Com qualquer assistente que leia arquivos do projeto: Cursor, Claude Code, Codex e outros. O roteiro é um
Markdown comum, sem depender de ferramentas de um produto específico.
</details>

<details>
<summary><b>Meu código é enviado para algum servidor?</b></summary>

Não pelo plugin: nada sai do domo. Os comentários ficam na IDE e no `.md` dentro do seu projeto, e o que
vai para a IA é decidido por você e pelo assistente que você usa. O plugin só usa a rede em dois casos: o
envio de um relatório de bug ao Linear, quando você configura o Linear e clica em **Enviar**, e a aba
Patrulha, que lê e comenta os pull requests do seu repositório no GitHub com a sua conta do IntelliJ.
</details>

<details>
<summary><b>A pasta <code>ironhawk/iron-dome/</code> vai encher de arquivos?</b></summary>

Não precisa. Na aba Radar, **Consolidar Histórico** junta todas as revisões encerradas num único
`historico-interceptacoes.md` e apaga os avulsos, e **Limpar Histórico** apaga o histórico de vez. A
revisão aberta nunca é tocada, e o que for apagado pode ser recuperado pelo Local History da IDE.
</details>

<details>
<summary><b>Quais linguagens são suportadas?</b></summary>

Os comentários funcionam em qualquer arquivo de texto: código, specs, documentação, configuração.
O Raio de Impacto é para classes Java e Kotlin.
</details>

<details>
<summary><b>A coluna de testes mede a cobertura de linhas?</b></summary>

Não. Ela mostra quais classes de teste usam a classe diretamente, sem executar os testes. Para medir
a cobertura de linhas, use o **Run with Coverage** do IntelliJ.
</details>

<details>
<summary><b>Por que colocar uma chave no <code>@DisplayName</code> da classe de teste?</b></summary>

A chave, entre colchetes no início do `@DisplayName("[Frete] Cálculo do frete")`, diz de que assunto
a classe trata. O Escudo agrupa as classes pela chave, então os testes do mesmo assunto ficam juntos
mesmo em pacotes diferentes. Classes sem `@DisplayName` ou sem chave continuam rodando, mas vão para o
grupo **Sem chave**, com a sugestão de como corrigir.
</details>

<details>
<summary><b>Como escrever o <code>@DisplayName</code> dos testes para aproveitar a formatação?</b></summary>

Descreva o cenário com as etapas e a expectativa, por exemplo
`@DisplayName("Dado que o cliente é VIP, quando compra acima de 500 então ganha 15% de desconto")` ou
`@DisplayName("não deve aceitar cupom vencido")`. Use crases para código (`` `null` ``) e uma seta para o
resultado esperado (`"soma 2 e 3 -> 5"`). Nada disso é obrigatório: um nome comum continua aparecendo
normalmente.
</details>

## 🛠️ Desenvolvimento

Requer JDK 21+.

```powershell
.\gradlew.bat runIde       # IDE de sandbox com o plugin
.\gradlew.bat test         # testes
.\gradlew.bat buildPlugin  # build/distributions/iron-dome-ia-<versão>.zip
```

> [!IMPORTANT]
> O `buildPlugin` roda os testes antes e não gera o `.zip` se algum falhar.

O código fica em `src/main/kotlin/com/mio/irondome`, com um pacote por mecanismo de defesa:

| Pacote | Mecanismo | O que tem |
| --- | --- | --- |
| `radar` | 📡 Radar | Comentários, formulário, margem do editor, cartões e a aba Radar |
| `comando` | 🎯 Centro de Comando | Geração e leitura do `.md`, histórico consolidado e as ações de salvar |
| `impacto` | 💥 Raio de Impacto | Diagrama de classes das dependências (só com o plugin Java) |
| `sentinela` | 👁️ Sentinela | Aba Sentinela e o diff dos arquivos não commitados |
| `escudo` | 🛡️ Escudo | Execução dos testes JUnit, formatação do `@DisplayName` e a aba Escudo (só com o plugin JUnit) |
| `patrulha` | 🛰️ Patrulha | Cliente do GitHub, checkout e revisão dos PRs, os pedidos em `.md` e as verificações de código, conversa e IA (só com os plugins Git e GitHub) |
| `caixapreta` | 📦 Caixa-Preta | Formulário de bug, print e gravação em GIF, leitura dos erros do plugin no `idea.log`, o `.md` do relatório, a issue no GitHub e a verificação de compatibilidade |
| `base` | 🏰 Base | Tool window, abas extras, a barra flutuante do editor e os componentes visuais compartilhados (cabeçalho de resumo, etiquetas, barras e linhas das árvores) |

O diagrama de classes é desenhado em SVG puro, sem biblioteca de terceiros.

---

<p align="center">
  Feito para quem quer a velocidade da IA com o código protegido pelo domo. 🛡️
</p>
