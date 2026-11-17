<p align="center">
  <img src="../src/main/resources/META-INF/pluginIcon.svg" width="110" alt="IronHawk IA">
</p>

<h1 align="center">Defesa: Iron Dome</h1>

<p align="center">
  <b>Revisão do código com IA, direto no IntelliJ.<br>
  Você marca o alvo, a IA intercepta e nenhum bug passa pelo domo.</b>
</p>

<p align="center">
  <a href="#como-funciona">Como funciona</a> &nbsp;·&nbsp;
  <a href="#marcar-e-disparar-alvos">Marcar e disparar alvos</a> &nbsp;·&nbsp;
  <a href="#ver-o-que-mudou">Ver o que mudou</a> &nbsp;·&nbsp;
  <a href="#medir-o-impacto">Medir o impacto</a> &nbsp;·&nbsp;
  <a href="#rodar-e-padronizar-testes">Rodar e padronizar testes</a> &nbsp;·&nbsp;
  <a href="#revisar-um-pr">Revisar um PR</a> &nbsp;·&nbsp;
  <a href="#configure-sua-ia">Configure sua IA</a> &nbsp;·&nbsp;
  <a href="#faq">FAQ</a>
</p>

<p align="center">
  <img src="images/fluxo.svg" width="900" alt="Fluxo da Defesa: marque o alvo no editor, envie as ordens em Markdown e a IA intercepta e marca como resolvido">
</p>

A Defesa é um dos dois sistemas do [IronHawk IA](../README.md). Ela traz para a IDE o fluxo do code review: você marca
os alvos linha a linha, como num pull request, e o plugin grava tudo como uma ordem de interceptação em Markdown, com
arquivo, linha e trecho citado. A IA segue a ordem, marca cada alvo como resolvido e você vê o resultado em verde no
editor. A IA comum aos dois sistemas, a segurança e o Diagnóstico estão em [Arsenal](arsenal.md).

<a id="como-funciona"></a>

## Como funciona

1. **Marque o alvo.** Clique no **+** da margem, dê duplo clique no número da linha ou use
   <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd>. Escolha o modo: *Aplicar direto* ou *Discutir antes de
   alterar*.
2. **Dispare.** **Disparar com IA** grava `ironhawk/iron-dome/ordem-interceptacao-<id>.md` e pede à IA que corrija os
   alvos. Sem IA configurada, grava o `.md` e copia o pedido para você colar na sua IA.
3. **A IA intercepta.** Ela aplica as correções e marca `[x]` em cada alvo; o plugin mostra o comentário em verde.

## O painel

As abas da Defesa ficam no painel **IronHawk IA**, com **Defesa** escolhido no título: **Radar**, **Sentinela**,
**Raio de Impacto**, **Escudo** e **Patrulha**, e o **Arsenal** no fim. Raio de Impacto, Escudo e Patrulha dependem de
plugins que já vêm no IntelliJ IDEA (Java, JUnit, Git e GitHub); sem eles, a aba não aparece e o
[Diagnóstico](arsenal.md#diagnostico) diz o motivo.

Todas as abas seguem o mesmo desenho:

- **Cabeçalho compacto:** uma linha com o ícone, o título e os indicadores. O aviso, quando há, fica logo abaixo, sempre
  à vista. A seta do canto expande o contexto e a barra de proporção; a escolha vale para todas as abas.
- **Barra com as ações principais.** O resto fica em **⋮ Mais ações**, no fim da barra.
- **Uma aba com erro não derruba as outras:** o lugar dela mostra o erro, com atalhos para o Diagnóstico e o Reportar Bug.

Com mais de um projeto na mesma janela (módulos anexados com *Attach*), o seletor no título do painel escolhe qual
projeto a Defesa vigia. Cada um tem os seus alvos e a sua pasta `ironhawk/iron-dome/`.

---

<a id="marcar-e-disparar-alvos"></a>

## Marcar e disparar alvos (Radar)

**Para que serve:** marcar no código o que a IA deve corrigir, mandar as ordens e acompanhar o que foi interceptado.
**Onde fica:** no editor (margem, menu do botão direito, barra flutuante) e na aba **Radar**.

### Marcar

| Como | O que marca |
| --- | --- |
| **+** na margem, duplo clique no número da linha ou <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd> | A linha ou o trecho selecionado |
| Botão direito no editor › **IronHawk IA: Marcar Trecho como Alvo** | O mesmo, pelo menu |
| Botão direito num arquivo ou pacote (árvore do projeto, aba do editor, Sentinela) › **IronHawk IA: Marcar Arquivo Inteiro como Alvo** / **Marcar Pacote como Alvo** | O arquivo ou o pacote inteiro, para pedidos como *"separe este pacote em dois"* |
| **Comentário Geral**, na barra da aba Radar | O projeto como um todo, sem arquivo nem linha |

- Os comentários acompanham o código quando linhas entram ou saem.
- O alvo de arquivo aparece no topo do editor. A árvore do projeto e a Sentinela mostram quantos alvos **pendentes** cada
  arquivo e pacote tem, com a mesma contagem.
- O **Comentário Geral** vai para a seção **Geral**, no início do `.md`, que a IA lê antes dos comentários por arquivo.
  Durante a revisão de um PR ele fica desligado, porque os comentários vão para o PR.
- **IronHawk IA: Cancelar Alvo do Trecho**, no botão direito, cancela os alvos não interceptados da seleção ou da linha.
- Rascunhos ficam guardados mesmo se você fechar a IDE.

**Dois modos por comentário:** *Aplicar direto*, para o que é óbvio, e *Discutir antes de alterar*, para a IA ler o
código e perguntar antes de mexer.

### Disparar

Há uma ação só: **Disparar com IA**. Ela está na barra flutuante do editor, na barra da aba Radar, no menu de um item do
Radar e, como **Disparar**, no cartão do alvo no editor.

- Com alvos selecionados na aba Radar (um comentário, vários com <kbd>Ctrl</kbd>+clique, um arquivo ou a revisão), age
  só neles. Sem seleção, age em todos os alvos ainda não interceptados.
- Grava o `.md` e pede à IA que corrija só esses alvos e marque `[x]` nos atendidos.
- Sem IA configurada, grava o `.md` e copia o pedido para você colar na sua IA. Vale também para *Discutir antes de
  alterar*.
- Durante a revisão de um PR na Patrulha, fica desligada: o checkout é o do PR e a IA não altera o código.

<kbd>Ctrl</kbd>+<kbd>S</kbd> só grava o `.md`, sem chamar a IA: **Save All** grava os alvos de todos os arquivos, e
**Save Document**, só os do arquivo atual. A IDE também salva sozinha (ao trocar de janela, por exemplo), e isso não grava
o `.md`.

**Discutir antes de alterar, com a IA pronta:** a IA roda só lendo e devolve as perguntas, que aparecem no topo da aba
Radar, um bloco por comentário, com as opções (a recomendada marcada) e um campo de texto livre. Pergunta sem resposta
usa a recomendação. **Enviar respostas** faz a IA aplicar o que foi decidido. Ela pode fazer mais uma rodada de
perguntas, se ainda tiver uma dúvida que muda o resultado; depois disso aplica, assume a melhor opção onde restar
dúvida e lista as suposições no resumo. **Cancelar discussão** deixa os comentários como estavam.

A IA é orientada a analisar os comentários em conjunto e a perguntar antes de aplicar comentários que se contradizem.
Quando ela marca `[x]`, o plugin percebe a mudança no `.md` e mostra o comentário como resolvido. Com todos resolvidos,
a revisão se encerra e o próximo comentário abre uma nova.

<details>
<summary><b>Ver um exemplo do <code>.md</code> gerado</b></summary>

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

**Comentário:**

Esse cálculo deveria considerar o peso dos itens?
````

</details>

### A aba Radar

A árvore mostra **revisão > arquivo ou pacote > comentário**. Um clique leva ao código, o duplo clique foca o editor e
o botão direito tem **Disparar com IA**, **Editar**, **Cancelar Alvo**, **Desfazer Cancelamento** e **Mostrar/Ocultar
no Editor** (este também traz de volta alvos de revisões encerradas). O cabeçalho tem quatro indicadores:
*não disparados*, *na mira*, *interceptados* e *para discutir*.

Sem nenhum alvo, a aba lembra os caminhos: o **+** na margem, o duplo clique no número da linha, o botão direito num
arquivo ou pacote, e o link **Comentário Geral**.

| Etiqueta | Significado |
| --- | --- |
| **Não disparado** | Novo ou editado, ainda fora do `.md` |
| **Na mira** | No `.md`, esperando a IA |
| **Interceptado** | A IA marcou `[x]` |
| **Cancelado no próximo disparo** | Você cancelou; sai do `.md` na próxima gravação |
| **Discutir antes** | A IA discute com você antes de alterar |
| **Marcado pelo Escudo** / **pelas Regras de IA** | Alvo criado pelo plugin, não pelo revisor; o `.md` traz a `**Origem:**` |

| Ações principais | Mais ações (⋮) |
| --- | --- |
| **Comentário Geral**, **Disparar com IA**, **Expandir**, **Recolher** | **Verificar Regras de IA** / **Última Verificação das Regras de IA**; **Consolidar Histórico** / **Limpar Histórico**; **Mostrar Alvos Interceptados** / **Ocultar Alvos Interceptados**; **Atualizar** |

**Verificar Regras de IA:** a IA, só lendo, confere se as alterações do projeto (as não commitadas e os commits da branch
além da base) cumprem as regras de IA dele: `CLAUDE.md`, `AGENTS.md`, `.cursorrules`, `.claude/rules`, `.cursor/rules` e
`.github/copilot-instructions.md`, na raiz e nas subpastas. Sem nenhum desses arquivos, avisa e não chama a IA. O
resultado abre num diálogo com a regra, o arquivo, a linha e o trecho de cada violação; **Enviar ao Radar** marca só as
que você escolher, sem duplicar as que já estão lá. As regras que não dá para conferir lendo o código ficam em
**Não verificável**.

**Histórico:** cada revisão encerrada fica em `ironhawk/iron-dome/` como um `ordem-interceptacao-<id>.md`.
**Consolidar Histórico** junta todas em `historico-interceptacoes.md` e apaga os avulsos; **Limpar Histórico** apaga os
avulsos e o consolidado (a mesma janela oferece **Consolidar em Vez Disso**). Os dois **não tocam na revisão aberta**, e
o que for apagado volta pelo **Local History** da IDE.

**Ocultar Alvos Interceptados** só tira os interceptados do editor: o `.md` não muda. **Ocultar**, no cartão, faz o
mesmo com um alvo.

---

<a id="ver-o-que-mudou"></a>

## Ver o que mudou antes de commitar (Sentinela)

**Para que serve:** vigiar os arquivos ainda não commitados, com o diff a um clique.
**Onde fica:** aba **Sentinela**.

| Ações principais | Mais ações (⋮) |
| --- | --- |
| **Inspecionar Diff**, **Agrupar Por**, **Filtrar**, **Expandir**, **Recolher** | **Abrir Arquivo**, **Marcar como Alvo**, **Atualizar** |

- **Agrupar Por:** diretório, raiz e pacote, só pacote ou tipo de alteração. A escolha fica salva.
- **Filtrar:** só alguns tipos de alteração, ou **Só com Alvos**. A busca procura no nome, na pasta e no pacote.
- Cada arquivo traz o tipo de alteração (modificado, novo, movido, fora do git) e quantos alvos pendentes tem.
- A lista acompanha commits, edições e reverts sozinha.

**Inspecionar Diff** também fica na barra flutuante do editor, quando o arquivo tem alterações não commitadas.

---

<a id="medir-o-impacto"></a>

## Medir o impacto de uma mudança (Raio de Impacto)

**Para que serve:** ver uma classe Java ou Kotlin e a vizinhança dela num diagrama de classes, antes de mexer.
**Onde fica:** aba **Raio de Impacto**. Ação principal: **Mirar Classe Atual** (também é o link do estado vazio).

O raio é de 1 nível, nas duas direções: quem depende da classe e de quem ela depende. Cada classe é uma caixa em três
partes, como na UML: o nome (com `«interface»`, `«enumeration»` ou `«annotation»`, e itálico nas abstratas), os atributos
e as operações, com a visibilidade (`+`, `-`, `#`, `~`). A classe analisada tem a borda destacada.

| Seta | Significa |
| --- | --- |
| Linha cheia, triângulo vazado | **Herança** |
| Linha tracejada, triângulo vazado | **Realização**: implementa a interface |
| Linha cheia, ponta aberta | **Associação**: tem um atributo do tipo da outra |
| Linha tracejada, ponta aberta | **Uso**: chama, instancia ou usa membros estáticos da outra |

- **Clique** seleciona a caixa, **dois cliques** abrem o código e <kbd>Ctrl</kbd>+clique mira a classe clicada.
- <kbd>/</kbd> foca a busca e <kbd>Enter</kbd> passa pelas classes encontradas. Arraste para mover; a roda aproxima e
  afasta.
- A tabela abaixo do diagrama lista cada classe com o pacote, as **aferentes** (quantas dependem dela: o que pode
  quebrar se você mudá-la) e as **eferentes** (de quantas ela depende).

O Raio de Impacto não mede risco nem cobertura de testes. Para cobertura de linhas, use o **Run with Coverage** da IDE.

---

<a id="rodar-e-padronizar-testes"></a>

## Rodar e padronizar testes (Escudo)

**Para que serve:** rodar os testes JUnit pela IDE e ver o resultado por `@Tag` ou por critério de aceite.
**Onde fica:** aba **Escudo**.

| Ações principais | Mais ações (⋮) |
| --- | --- |
| **Executar Todos os Testes** (vira **Parar os Testes**), **Padronizar Testes**, **Mostrar Só as Falhas**, **Organizar por** | **Gerar Critérios com IA**, **Recarregar Critérios e Cucumber**, **Expandir**, **Recolher** |

<p align="center">
  <img src="images/escudo-detalhes.png" width="820" alt="Detalhes de um teste com a seção Cenário e de uma execução parametrizada com os parâmetros">
</p>

**Executar** compila o projeto e roda todos os testes JUnit pelo runner da IDE, não pelo Gradle; a execução também
aparece na janela Run. O resultado chega ao vivo, e o cabeçalho diz se o escudo está **íntegro** ou **rompido**.

**Organizar por:**

- **Tag:** a árvore vem por `@Tag` do JUnit. Vale a tag do método e, sem ela, a da classe. `@Tags` e anotações próprias
  marcadas com `@Tag` também contam. Um teste com duas tags aparece nas duas; os sem tag ficam em **Sem tag**.
- **Critério:** com o catálogo em `ironhawk/iron-dome/criterios/`, a árvore vem por critério de aceite, com o status de
  cada um e um placar no cabeçalho. Sem catálogo, essa opção fica desligada. O contrato está em
  [Escudo: critérios de aceite](escudo-criterios.md).

**Cucumber de fora da IDE:** os cenários rodados pelo CLI do Cucumber (XML do plugin `junit:`) entram nos mesmos
agrupamentos, com a pílula **Cucumber**. O subtítulo mostra a idade do XML, como *"Cucumber (XML há 5 min): 12 cenários,
1 falhando"*, e esses cenários não mudam o veredito da execução da IDE. Um cenário repetido em vários XMLs aparece uma
vez, com o resultado do mais novo. Detalhes em [Cucumber](escudo-criterios.md#cucumber-opcional).

**Padronizar Testes:**

- **Com catálogo de critérios**, a IA liga os testes aos critérios: deixa o `@DisplayName` em Dado/Quando/Então, põe as
  tags `criterio:` (e `nivel:` quando evidente) e um `@DisplayName` na classe que não tem. Só mexe em anotações, nunca na
  lógica, e o aviso do fim traz **Desfazer Alterações da IA**.
- **Sem catálogo**, marca no Radar as classes sem `@DisplayName` (as selecionadas ou, sem seleção, todas), como alvos
  **Marcados pelo Escudo**. Disparar esses alvos faz a IA corrigir. Uma classe que já tem alvo aberto do Escudo não é
  marcada de novo, e mesmo durante a revisão de um PR eles vão para o Radar.

**Falhas:** as classes com falha se abrem sozinhas, o teste mostra a primeira linha do erro e o painel de detalhes traz o
motivo e a pilha. **Explicar Falha com IA** fica no botão direito e como link nos detalhes do teste com falha: a IA
explica a causa e propõe a correção, sem alterar nada; a resposta oferece **Corrigir com IA**.

**Nomes formatados.** O `@DisplayName` de cada teste ganha destaque:

| Escrito no `@DisplayName` | Como aparece |
| --- | --- |
| `Dado que…`, `quando…`, `então…` (ou `given`, `when`, `then`) | Etiquetas **DADO**, **QUANDO** e **ENTÃO** |
| `deve…`, `should…` / `não deve…`, `should not…` | Verbo em negrito; o negativo em laranja |
| `` `código` `` e `"strings"` | Fundo lilás |
| `IllegalStateException`, `…Error` | Fundo vermelho |
| `200`, `R$ 200`, `15%` | Azul em negrito |
| `soma 2 e 3 -> 5` (`->`, `=>`, `→`) | Seta destacada e o resultado em verde |
| `[1] cep=01001-000, valor=10.0` (parametrizados) | Etiqueta **#1** e cada parâmetro destacado |

"Dado" sozinho também quer dizer informação ("valida os dados do cliente"), então só vira etapa no início do nome ou na
forma "dado que". O painel de detalhes repete a formatação e mostra o **Cenário** em etapas e os **Parâmetros desta
execução**.

---

<a id="revisar-um-pr"></a>

## Revisar um PR (Patrulha)

**Para que serve:** revisar os pull requests do GitHub na IDE. No modo revisão do PR, os comentários do editor vão para o
PR, não para a IA. Depois, conferir se cada pedido foi atendido.
**Onde fica:** aba **Patrulha**. Usa a conta do GitHub do IntelliJ (**Settings > Version Control > GitHub**).

| Ações principais | Mais ações (⋮) |
| --- | --- |
| **Revisar Este PR** (vira **Atualizar ou Encerrar Revisão** / **Encerrar Revisão**), **Ver Alterações**, **Revisar com IA**, **Enviar ao GitHub**, **Concluir Revisão**, **Atualizar** | **Ligar/Desligar Respostas da IA**, **Conferir Respostas Agora**, **Minhas Instruções e Regras para a IA**; **Responder**, **Editar**, **Excluir**, **Excluir Todos**; **Mostrar/Ocultar no Editor**, **Verificar Pedidos**; **Abrir .md dos Pedidos**, **Abrir no GitHub**; **Entrar com Token** |

O botão direito num item mostra só as ações que valem para ele.

1. **Escolha o PR.** A aba lista todos os PRs abertos do remote do GitHub, com o autor, as branches e quantos pedidos já
   foram atendidos. **Arquivos alterados** mostra o que mudou por pasta, com as linhas `+`/`−` e os comentários; duplo
   clique ou **Ver Alterações** abre o diff do PR. Se a organização bloquear o login pelo navegador, use **Entrar com
   Token** (token classic com escopo `repo`).
2. **Revise.** **Revisar Este PR** traz o código para a branch local `pr-<número>` e liga o modo revisão. O que não
   estiver commitado (fora de `ironhawk/` e `.idea/`) vai para o stash, com a sua aprovação antes. No modo revisão:
   - o formulário de comentário fica roxo, com **Adicionar ao PR**, e o comentário fica *Não enviado* até você enviar;
   - o editor mostra o que o PR mudou, como o diff do GitHub: linhas novas em verde, alteradas em azul e um triângulo
     vermelho onde linhas saíram;
   - **Sugerir código** abre um editor sobre uma cópia do arquivo, com o trecho editável. Nas linhas do diff vira uma
     sugestão que o autor aplica com um clique; fora do diff, vai como código de exemplo;
   - **Revisar com IA** faz a IA ler o PR e propor comentários, que entram como não enviados para você revisar.

   Trocar de branch por fora da Patrulha encerra a revisão; o aviso oferece voltar ao PR e devolver o stash.
   **Encerrar Revisão** oferece voltar para a branch de antes ou **Encerrar sem Voltar**.
3. **Envie.** **Enviar ao GitHub** sempre envia como **rascunho**: a revisão pendente, que só você vê. O diálogo mostra
   onde cada comentário vai entrar e deixa escolher, um a um, enviar, deixar para depois ou excluir. Linhas que o PR
   altera viram comentários de linha; linhas fora do diff, comentário do arquivo; fora dos arquivos do PR, o texto
   geral da revisão.
4. **Publique.** **Concluir Revisão** é a única forma de publicar: escolha o veredito (**Aprovar**, **Solicitar
   alterações** ou **Só comentar**), e os comentários não enviados e o rascunho vão junto, na mesma revisão. No próprio
   PR, o GitHub só deixa comentar.
5. **Acompanhe no código.** Durante a revisão, os pedidos enviados e as conversas de outras pessoas aparecem no editor
   como cartões, com o status (*Enviado*, *Atendido*, *Aberta*, *Resolvida*...). Na barra do canto do editor e no botão
   direito:
   - **Minimizar Comentários do PR** recolhe todos numa linha e vira **Expandir Comentários do PR**;
   - **Ocultar Comentários do PR** tira todos do editor sem apagar nenhum e vira **Mostrar Comentários do PR**, que traz
     também os que você ocultou um a um.

   Na aba, **Mostrar/Ocultar no Editor** faz o mesmo para o PR selecionado. Se alguém editar ou apagar um comentário
   direto no GitHub, a Patrulha sincroniza ao atualizar e em **Verificar Pedidos**.
6. **Verifique.** Os pedidos ficam em `ironhawk/iron-dome/pull-requests/pr-<número>.md`. **Verificar Pedidos** confere
   cada um:

| Verificação | Como funciona |
| --- | --- |
| **Código** | Compara o trecho comentado com o head atual do PR: *Como sugerido*, *Alterado*, *Sem alteração*, *Só mudou de lugar*, *Arquivo removido* ou *Sem commits novos* |
| **Conversa** | Lê a conversa no PR: *Resolvida*, *Respondida* (com a última resposta do autor) ou *Aberta* |

Um pedido conta como atendido quando o código mudou ou a conversa foi resolvida.

**Respostas da IA.** Com a revisão ativa, **Ligar Respostas da IA** liga o modo automático no PR selecionado; ele segue
ligado até você desligar (ou até o PR fechar), mesmo depois de encerrar a revisão. A cada 2 minutos, com a IDE aberta, a
Patrulha confere as conversas que você abriu. Quando alguém responde, a IA lê a conversa, o pedido e o código no head do
PR e decide:

- **Responder:** publica em seu nome e espera o dev de novo.
- **Concluir:** quando o dev corrigiu ou explicou bem, publica a mensagem final e resolve a conversa.
- **Passar para você:** quando a conversa pede uma decisão sua, não publica nada e avisa.

Cada resposta termina com a nota *"Resposta automática do IronHawk IA em nome de @…"*. Cada conversa tem no máximo 5
rodadas da IA. O nó **Respostas da IA**, no PR, mostra o passo de agora, a hora da próxima conferência ou o motivo de ter
parado, e o histórico. **Conferir Respostas Agora** não espera os 2 minutos.

**Minhas Instruções e Regras para a IA** guarda, nas suas palavras, como você avalia um PR: o que bloqueia, o que é só
sugestão, o tom. Vale para todos os projetos. No mesmo diálogo, **Regras deste projeto** vincula arquivos de regras
(`AGENTS.md`, `CLAUDE.md`, `.cursor/rules`...); **Detectar Regras do Projeto** acha os que existem. A IA segue os dois na **Revisão com IA**
e nas respostas automáticas.

---

<a id="inteligencia-da-ia"></a>

## Inteligência da IA

Em **Settings > Tools > IronHawk IA > Defesa: Inteligência da IA**:

- **Aprender com o que o time aceita e descarta** das sugestões da IA. Fica em
  `ironhawk/iron-dome/defesa/aprendizado-revisao.md`, onde você também pode escrever regras do time, uma por linha.
- **Anexar o dossiê das classes** aos pedidos: histórico, comentários antigos e decisões (em
  `ironhawk/iron-dome/defesa/decisoes.md`).
- **Avisar quando dois comentários apontam para o mesmo trecho.** O aviso oferece **Pedir Síntese à IA**. Um par já
  avisado não volta a avisar a cada abertura; um par novo avisa.

Dossiê e aprendizado alimentam hoje a **Revisão com IA** da Patrulha.

---

<a id="configure-sua-ia"></a>

## Configure sua IA

O `.md` gerado já traz as instruções para a IA. Para que ela trate as revisões sem você precisar pedir (útil quando você
cola o pedido na sua IA ou usa um assistente fora do Arsenal), adicione ao `AGENTS.md` (ou a uma rule do Cursor) do
projeto revisado:

```markdown
## Revisões em `ironhawk/iron-dome/`

Quando houver arquivos em `ironhawk/iron-dome/ordem-interceptacao-*.md` com comentários pendentes (`[ ]`), trate cada
um no arquivo indicado na seção dele (código, spec, documentação ou configuração), localizando o
ponto pelo trecho citado, e marque-o como `[x]` ao terminar. Não edite o restante do `.md`.
A seção `## Geral`, no início, vale para o projeto todo: leia-a antes das outras e decida onde mexer.
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
Os arquivos em `ironhawk/iron-dome/pull-requests/` são pedidos para o autor de um PR: não os aplique.
```

Depois disso, basta dizer à IA algo como *"aplique a revisão"*.

<a id="faq"></a>

## Perguntas frequentes

<details>
<summary><b>Com qual IA funciona?</b></summary>

O `.md` é Markdown comum, então qualquer assistente que leia arquivos do projeto o segue. Para a IA rodar direto da IDE,
o Arsenal usa o Cursor, o Claude Code ou o Codex (na aba ou no terminal) e o Antigravity (só no terminal). Veja
[Arsenal](arsenal.md).
</details>

<details>
<summary><b>Meu código é enviado para algum servidor?</b></summary>

Não pelo plugin. Os comentários ficam na IDE e no `.md` do projeto, e o que vai para a IA é decidido por você e pelo CLI
que você configurou. O plugin usa a rede para o GitHub: a Patrulha e o Rádio leem e comentam PRs com a sua conta do
IntelliJ, e o Reportar Bug cria uma issue pública quando você clica em **Publicar no GitHub**.
</details>

<details>
<summary><b>A pasta <code>ironhawk/iron-dome/</code> vai encher de arquivos?</b></summary>

Não precisa. No ⋮ da aba Radar, **Consolidar Histórico** junta as revisões encerradas num arquivo só, e **Limpar
Histórico** apaga de vez. A revisão aberta nunca é tocada.
</details>

<details>
<summary><b>Quais linguagens são suportadas?</b></summary>

Os alvos funcionam em qualquer arquivo de texto: código, specs, documentação, configuração. O Raio de Impacto é para
classes Java e Kotlin, e o Escudo, para testes JUnit 5 (e cenários Cucumber).
</details>

<details>
<summary><b>Ainda preciso da chave <code>[Chave]</code> no <code>@DisplayName</code>?</b></summary>

Não. O Escudo não agrupa mais pela chave e não a cobra. Organize por `@Tag` ou por critério de aceite.
</details>

## Código

O código da Defesa fica em `src/main/kotlin/com/mio/irondome`, um pacote por mecanismo: `radar` (alvos, editor e aba
Radar), `comando` (o `.md` e o histórico), `interceptor` (a IA do Arsenal), `sentinela`, `impacto` (só com o plugin
Java), `escudo` (só com o plugin JUnit), `patrulha` (só com os plugins Git e GitHub), `defesa` (Inteligência da IA),
`caixapreta` (Diagnóstico e Reportar Bug) e `base` (o que as abas compartilham). O diagrama de classes é desenhado em
SVG puro, sem biblioteca de terceiros.
