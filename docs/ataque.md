<p align="center">
  <img src="../src/main/resources/META-INF/pluginIcon.svg" width="110" alt="IronHawk IA">
</p>

<h1 align="center">Ataque: Tomahawk</h1>

<p align="center">
  <b>Desenvolvimento guiado pela spec, etapa por etapa, com a sua IA.</b>
</p>

<p align="center">
  <a href="#como-funciona">Como funciona</a> &nbsp;·&nbsp;
  <a href="#preparar-o-projeto">Preparar o projeto</a> &nbsp;·&nbsp;
  <a href="#lancar-as-etapas">Lançar as etapas</a> &nbsp;·&nbsp;
  <a href="#entregar-num-pr">Entregar num PR</a> &nbsp;·&nbsp;
  <a href="#revisar-o-que-foi-gerado">Revisar o que foi gerado</a> &nbsp;·&nbsp;
  <a href="#acompanhar-a-missao">Acompanhar a missão</a> &nbsp;·&nbsp;
  <a href="#responder-aos-comentarios-dos-seus-prs">Responder aos comentários</a>
</p>

O Ataque é um dos dois sistemas do [IronHawk IA](../README.md). Ele conduz o Spec-Driven Development do
[Spec Kit](https://github.com/github/spec-kit): a IA escreve a spec, o plano, as tarefas e o código da feature, uma
etapa por vez, e você revisa entre uma e outra. A IA é a do [Arsenal](arsenal.md), comum aos dois sistemas.

<a id="como-funciona"></a>

## Como funciona

As abas do Ataque ficam no painel **IronHawk IA**, com **Ataque** escolhido no título:

| Aba | Para que serve |
| --- | --- |
| **Lançador** | Escolher a feature, lançar cada etapa do Spec Kit e, no fim, entregar num PR |
| **Avaliação** | Ler o que o Spec Kit gerou (spec, plano, tarefas, checklists), só leitura |
| **Telemetria** | Acompanhar a missão em voo, o log ao vivo e o histórico das execuções |
| **Rádio** | Os comentários dos seus PRs no GitHub, com a resposta escrita pela IA |
| **Arsenal** | A IA, onde rodar e as proteções, comum à Defesa ([Arsenal](arsenal.md)) |

O fluxo é: **Regras de Engajamento** uma vez por projeto, depois **Designar Alvo** com a descrição da feature, e as
etapas seguintes na ordem, até **Avaliação de Danos** convergir. Cada etapa lançada é uma *missão*.

As abas seguem o desenho da Defesa: cabeçalho compacto de uma linha (a seta expande o contexto), as ações principais na
barra e o resto em **⋮ Mais ações**.

---

<a id="preparar-o-projeto"></a>

## Preparar o projeto

O Ataque precisa do Spec Kit no projeto (a pasta `.specify/`) e do CLI `specify` na máquina.

- **Inicializar:** **Tools > IronHawk IA: Inicializar Spec Kit**, ou o botão **Inicializar o Spec Kit** no Lançador e
  no Arsenal (aba **Recursos**). Roda `specify init --here` no terminal da IDE, com a integração do CLI de IA
  configurado. **Copiar Comando** dá o comando para rodar fora da IDE.
- **Sem o `specify`:** **Instalar o Spec Kit** roda a instalação no terminal da IDE (`uv tool install specify-cli`).
- **`specify` com defeito** (falta o pacote na instalação do `uv`): **Reinstalar o Spec Kit** roda
  `uv tool install --force specify-cli`.
- **Versão nova:** **Atualizar o Spec Kit**.
- **Outra integração:** se o projeto foi inicializado para outro CLI, o Arsenal oferece **Instalar a Integração**.

As missões rodam na aba, no devcontainer do projeto (`.devcontainer/tomahawk/devcontainer.json`), ou no terminal da IDE.
O Arsenal cria e confere o devcontainer: veja [Onde rodar a IA](arsenal.md#onde-rodar-a-ia).

---

<a id="lancar-as-etapas"></a>

## Lançar as etapas (Lançador)

**Para que serve:** escolher a feature, ver em que pé está cada etapa e lançar a próxima.
**Onde fica:** aba **Lançador**. Barra: **Abrir Artefato**, **Abrir a Spec**, **Arsenal**.

À esquerda, o seletor **Feature** e a lista das nove etapas: a constituição, que vale para o projeto todo, e as oito da
feature, com o estado de cada uma e a **Próxima** em destaque.

| # | Etapa | Comando | O que faz |
| --- | --- | --- | --- |
| — | **Regras de Engajamento** | `constitution` | Os princípios do projeto; uma vez por projeto |
| 1 | **Designar Alvo** | `specify` | A spec da feature: o quê e por quê. Cria `specs/NNN-nome` |
| 2 | **Reconhecimento** | `clarify` | Até cinco perguntas sobre os pontos vagos da spec (opcional) |
| 3 | **Plano de Voo** | `plan` | O plano técnico e os artefatos de design |
| 4 | **Pré-voo** | `checklist` | Checklists de qualidade dos requisitos (opcional) |
| 5 | **Waypoints** | `tasks` | As tarefas, por dependência |
| 6 | **Simulação de Rota** | `analyze` | Consistência entre spec, plano e tarefas, só leitura (opcional) |
| 7 | **Lançamento** | `implement` | Executa as tarefas |
| 8 | **Avaliação de Danos** | `converge` | Confere o código contra a spec; acrescenta tarefas se faltar algo |

O cabeçalho mostra a feature, quantas etapas estão concluídas, as tarefas feitas e as abertas, e a próxima etapa.

À direita, o formulário da etapa escolhida, em seções:

- **A etapa:** pílulas (*Entrada obrigatória*, *Só leitura*, *Faz perguntas*), uma frase do que ela faz e o motivo do
  estado atual. **Detalhes**, recolhido, traz o propósito completo e o link **Abrir o comando**.
- **Executar:** a **Entrada** (`$ARGUMENTS`), **Lançar** (<kbd>Ctrl</kbd>+<kbd>Enter</kbd>) **em** Devcontainer ou
  Terminal, e **Copiar Comando**, para colar no chat do agente. No devcontainer, a IA roda sem perguntar e a Telemetria
  acompanha cada passo; no terminal, você conversa com a IA e aprova os comandos.
- **Skills extras:** um botão por skill marcada no Arsenal (veja [Skills extras](arsenal.md#recursos)). Pede uma
  instrução opcional e roda a skill sobre a feature escolhida, como uma tarefa da IA comum, fora da sequência.
- **Entrega (commit, push e PR):** recolhida, com um resumo no título. Veja [Entregar num PR](#entregar-num-pr).

Com uma missão em voo, o formulário avisa e traz o link **Acompanhar na Telemetria**.

**A feature ativa.** Escolher outra feature no seletor só muda o que se vê. Ela vira a ativa (`.specify/feature.json`) só
quando você lança uma etapa ou copia o comando. **+ Nova feature** começa por **Designar Alvo**.

---

<a id="entregar-num-pr"></a>

## Entregar num PR (Entrega)

**Para que serve:** commitar, enviar e abrir o pull request da feature no GitHub. Antes se chamava Extração.
**Onde fica:** no fim do Lançador, na seção **Entrega (commit, push e PR)**, recolhida até você abrir.

- **O que fazer:** *Só commitar*, *Commitar e enviar* ou *Commitar, enviar e abrir PR*.
- **Branch** (uma nova é criada a partir da atual) e **Base do PR**.
- **Mensagem do commit:** a primeira linha é o título; o corpo vem depois de uma linha em branco.
- **Título do PR** e **Descrição do PR.**
- **Gerar com IA**, ao lado da mensagem do commit e da descrição do PR. No commit, a IA do Arsenal escreve a mensagem a
  partir das alterações não commitadas e do diff. No PR, escreve o título e a descrição a partir dos commits da branch e
  do diff contra a base.
- **Abrir como rascunho.** Se a Avaliação de Danos ainda não convergiu, o aviso sugere abrir como rascunho.

Os campos começam com o que sai da spec e das tarefas da feature; o que você escreve fica até a entrega dar certo. Com
um PR já aberto, o botão envia para ele, e **Ver os Comentários** leva ao Rádio. Abrir PR pede a conta do GitHub da IDE;
commitar e enviar funcionam sem ela.

---

<a id="revisar-o-que-foi-gerado"></a>

## Revisar o que foi gerado (Avaliação)

**Para que serve:** ler, sem alterar, o que o Spec Kit gerou em cada feature, antes de seguir para a próxima etapa.
**Onde fica:** aba **Avaliação**. Barra: **Abrir**, **Só a Feature Ativa**, **Expandir**, **Recolher**.

- A árvore mostra a constituição e, por feature, a spec (histórias, requisitos, critérios), os pontos
  `[NEEDS CLARIFICATION]` em **Esclarecimentos pendentes**, o plano, o design, as tarefas por fase e os checklists.
- O cabeçalho mede a qualidade: itens **a esclarecer**, **itens de checklist abertos**, **histórias**, **requisitos** e a
  **última análise** da Simulação de Rota.
- Duplo clique abre o artefato na **Visualização** (o Markdown formatado, com os diagramas), ou o trecho no texto.
- Um artefato que ainda falta leva à etapa certa no Lançador.

A Visualização bloqueia scripts vindos dos `.md`: só os da própria página rodam.

---

<a id="acompanhar-a-missao"></a>

## Acompanhar a missão (Telemetria)

**Para que serve:** ver a missão em voo e o histórico das execuções.
**Onde fica:** aba **Telemetria**. Barra: **Log ao Vivo**, **Abrir Registro**, **Filtrar** (pela situação ou só a feature
ativa).

- No topo, o progresso da missão em voo e o gráfico com as durações das últimas execuções.
- No meio, o histórico por dia (Hoje, Ontem...), com a situação, o veredito e o tempo de cada execução.
- Embaixo, o log ao vivo de cada passo da IA (leituras, edições, comandos e a resposta), ou o registro da execução
  escolhida, formatado. Duplo clique abre o registro no editor.

Os registros ficam em `ironhawk/tomahawk/execucoes/`.

---

<a id="responder-aos-comentarios-dos-seus-prs"></a>

## Responder aos comentários dos seus PRs (Rádio)

**Para que serve:** ver as conversas dos PRs que você abriu e responder, com a IA escrevendo a resposta se quiser.
**Onde fica:** aba **Rádio** (precisa dos plugins Git e GitHub da IDE).

| Ações principais | Mais ações (⋮) |
| --- | --- |
| **Abrir no GitHub**, **Marcar Tudo como Lido**, **Filtrar**, **Entrar no GitHub** (quando precisa) | **Ler os PRs de Novo**, **Expandir**, **Recolher** |

- A árvore lista os seus PRs abertos e as conversas de cada um, com o que é novo e o que espera resposta.
  **Filtrar**: *Aguardando Resposta*, *De Outros Devs* ou *Todas*.
- Escolhida uma conversa, embaixo aparecem o trecho do diff, os comentários e a caixa de resposta, com **Responder com
  IA** (o texto da caixa vira a orientação da IA), **Marcar como Lida** e **Enviar Resposta**.
- O Rádio relê os PRs a cada 3 minutos; **Ler os PRs de Novo** não espera.

O Rádio é para os PRs que **você** abriu. Para revisar o PR de outra pessoa, use a [Patrulha](defesa.md#revisar-um-pr).

---

## Proteções das missões

As missões rodam com as regras que barram comandos perigosos, um vigia que para a missão num comando perigoso ou numa
escrita fora do projeto, e um ponto de restauração no Git (`refs/tomahawk/`) antes de cada missão. **Desfazer a Última
Missão** volta o projeto para esse ponto, depois de mostrar o que mudou. Dá para desligar as proteções no Arsenal ou em
**Settings > Tools > IronHawk IA > Ataque: Spec Kit e Proteções**. Detalhes em [Segurança](arsenal.md#seguranca).

## Código

O código do Ataque fica em `src/main/kotlin/com/mio/tomahawk`: `ui` (as abas), `speckit` (leitura do `.specify/` e das
features), `run` (missões, devcontainer, proteções e Spec Kit), `delivery` (Entrega e Rádio, com o GitHub), `view`
(a Visualização dos artefatos) e `settings`.
