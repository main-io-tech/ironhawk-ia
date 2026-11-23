<p align="center">
  <img src="../src/main/resources/META-INF/pluginIcon.svg" width="110" alt="IronHawk IA">
</p>

<h1 align="center">Arsenal</h1>

<p align="center">
  <b>A IA comum à Defesa e ao Ataque: o CLI, onde ele roda, a segurança e as execuções.</b>
</p>

<p align="center">
  <a href="#configurar-a-ia">Configurar a IA</a> &nbsp;·&nbsp;
  <a href="#onde-rodar-a-ia">Onde rodar</a> &nbsp;·&nbsp;
  <a href="#seguranca">Segurança</a> &nbsp;·&nbsp;
  <a href="#recursos">Recursos</a> &nbsp;·&nbsp;
  <a href="#execucoes">Execuções</a> &nbsp;·&nbsp;
  <a href="#settings">Settings</a> &nbsp;·&nbsp;
  <a href="#diagnostico">Diagnóstico</a> &nbsp;·&nbsp;
  <a href="#reportar-bug">Reportar Bug</a>
</p>

O Arsenal é a última aba do painel **IronHawk IA**, a mesma com **Defesa** ou **Ataque** escolhido no título. Ele chama a
IA pelo CLI dela, na sua máquina ou no devcontainer do projeto, para as tarefas da [Defesa](defesa.md) e as missões do
[Ataque](ataque.md). O que vale só para um sistema leva a etiqueta **Defesa** ou **Ataque**.

## CLIs suportados

| CLI | Executável | Na aba (devcontainer) | No terminal da IDE |
| --- | --- | --- | --- |
| **Cursor** | `agent` | Sim | Sim |
| **Claude Code** | `claude` | Sim | Sim |
| **Codex** | `codex` | Sim | Sim |
| **Antigravity** | `agy` | Não | Sim |

O Antigravity não está no devcontainer: com ele, a IA só roda no terminal e não lança missões do Ataque. O `agy -p` pode
travar quando chamado por outro programa; o tempo máximo interrompe.

## A aba Arsenal

O cabeçalho mostra o estado da IA (*IA pronta*, *IA não configurada*, *Teste a integração*, *IA trabalhando*, *Missão em
voo*). Abaixo, quatro abas internas: **IA**, **Execução**, **Recursos** e **Execuções**. Uma aba com pendência ganha um
ponto amarelo (falta fazer) ou vermelho (quebrado), e o link **Resolver: …**, no topo, leva a ela. O Arsenal abre onde há
algo a resolver; com tudo pronto, abre em **Execuções**.

Enquanto a IA trabalha, a aba que pediu e o Arsenal mostram uma barra com o passo atual, o tempo, a última linha que a IA
escreveu e **Cancelar**. O ícone da aba e o do painel viram o ícone de IA. No terminal, a espera acaba assim que o CLI
termina ou a aba do terminal é fechada, mesmo sem resposta (por exemplo, num erro de login), e os botões de IA voltam na
hora em vez de esperar o tempo limite.

<a id="configurar-a-ia"></a>

## Configurar a IA (aba IA)

Na primeira vez que o Arsenal abre, a IA se configura sozinha: o plugin procura os CLIs instalados e testa cada um, com o
modelo padrão, até um responder. **Configurar Automaticamente** refaz isso a qualquer hora.

Na configuração manual:

- **CLI**, **Onde está o CLI** (nesta máquina ou, no Windows, **No WSL**, com a distribuição), **Executável** (vazio
  procura no PATH e nas pastas de instalação) e **Modelo** (vazio usa o padrão da sua conta).
- **Chave de API do Cursor**, opcional: sem ela, vale o `agent login`. A chave é criada em cursor.com/dashboard ›
  Integrations e fica no cofre de senhas da IDE. Campo vazio mantém a chave salva; **Remover a chave salva** apaga. Vale
  igual no painel e nas Settings.

**Tempo máximo** e **Argumentos extras** ficam só nas Settings (**Arsenal (IA)**).

**Onde está o CLI** vale para o terminal e para o teste. Na aba, a IA usa o CLI do devcontainer.

**Salvar e Testar** faz uma pergunta curta à IA. **Os botões de IA só se habilitam depois que o teste passa**, e trocar o
CLI, o executável, o modelo ou a chave pede um teste novo. **Testar no Terminal** abre a aba *IA: Teste* do terminal, pede
à IA que grave OK num arquivo e mostra o tempo e o shell; se a resposta não chega, o terminal mostra o que deu errado (o
comando, o login ou o PATH).

<a id="onde-rodar-a-ia"></a>

## Onde rodar a IA (aba Execução)

A aba Execução tem quatro seções, cada uma recolhível pelo título; a que você recolhe fica recolhida da próxima vez:

| Seção | O que tem |
| --- | --- |
| **Onde rodar a IA** | O modo, comum aos dois sistemas, e o que vale em cada um |
| **Defesa** | A contenção da IA, a pergunta antes de alterar arquivos e as flags de cada CLI |
| **Ataque** | As proteções das missões, **Desfazer a Última Missão** e as flags de cada CLI |
| **Devcontainer** | O estado do container, **Criar o Devcontainer** e **Subir e Conferir** |

As seções Defesa e Ataque só aparecem com o sistema ligado.

Os modos, em **Onde rodar a IA**:

- **Na aba, no devcontainer:** a IA roda sem perguntar, dentro do devcontainer do projeto
  (`.devcontainer/tomahawk/devcontainer.json`), que só enxerga a pasta do projeto. O plugin sobe o container (ou
  reaproveita o que está de pé) e confere o isolamento. Sem o devcontainer, a IA não roda na aba.
- **No terminal da IDE:** cada pedido abre uma aba do terminal com o CLI à vista, nesta máquina. A IA pede a sua aprovação
  antes de agir. A barra da aba mostra **Mostrar Terminal** e **Parar de Esperar**.
- **Perguntar a cada vez.**

No Lançador, cada etapa do Ataque pode trocar entre Devcontainer e Terminal na hora de lançar. Sem o plugin de terminal da
IDE, a IA só roda na aba.

**Devcontainer:** **Criar o Devcontainer** grava o `devcontainer.json` com os CLIs de IA e o isolamento; **Abrir
devcontainer.json** e **Subir e Conferir** sobem o container e conferem se ele isola a máquina. A primeira subida demora.

**Login no container.** O Cursor entra pela chave de API da aba IA. O Codex e o Claude Code usam o login feito dentro do
container ou a chave desta máquina (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`). Para entrar lá dentro:

```bash
devcontainer exec --workspace-folder . --config .devcontainer/tomahawk/devcontainer.json codex login
devcontainer exec --workspace-folder . --config .devcontainer/tomahawk/devcontainer.json claude
```

<a id="seguranca"></a>

## Segurança

A seção **Onde rodar a IA** diz o que vale em cada modo:

| Onde | Como a IA roda |
| --- | --- |
| **Na aba** | Sem perguntar, só no devcontainer, que enxerga só a pasta do projeto |
| **No terminal** | Nesta máquina, pedindo a sua aprovação antes de agir |
| **Textos e revisões** | Sempre só leitura, nos dois modos |

Nas tarefas só de leitura e no terminal, os **argumentos extras perigosos são removidos**: nada nos argumentos extras
libera permissão total fora do devcontainer.

**Contenção da IA, na seção Defesa, sempre ligada:**

- **Ponto de restauração:** antes de cada tarefa que altera arquivos, o plugin tira uma foto do projeto no git, inclusive
  o que não foi commitado, numa referência própria (`refs/iron-dome/antes-da-ia/`, as 10 últimas), sem mexer no índice,
  na branch nem nos stashes. O aviso do fim traz **Desfazer Alterações da IA**. Fora de um repositório git, o ponto é o
  do Local History.
- **Ambiente contido:** sem `git push` e só com a credencial do CLI; tokens e chaves do ambiente não chegam à IA.
- **Vigia:** a IA é parada se tentar apagar pastas, descartar alterações do git, reescrever o histórico, rodar como
  administrador ou escrever fora do projeto ou no `.git`. O aviso diz o que ela tentou.
- **Perguntar antes de cada tarefa que altera arquivos na aba:** caixa opcional para confirmar cada uma.

Durante a revisão de um PR na Patrulha, a IA não altera o código: o checkout é o do PR. E, em qualquer pedido que altera
código, ela é instruída a não seguir os pedidos de pull request de `ironhawk/iron-dome/pull-requests/`.

**Proteções das missões, na seção Ataque, desligáveis:**

- **Barrado antes de rodar:** apagar pastas, formatar discos, pedir administrador, instalar programas, `git push`,
  `reset --hard`, `clean`.
- **Vigia:** a missão para num comando perigoso, numa escrita fora do projeto ou no `.git`, ou ao ler credenciais.
- **Ponto de restauração:** o projeto vai para o Git (`refs/tomahawk/`) antes de cada missão, sem mexer na sua branch.
  **Desfazer a Última Missão** volta para ele, depois de mostrar o que mudou.
- **Sem segredos:** tokens e chaves do ambiente (GitHub, nuvem, npm) não chegam à IA.

Desligadas, as missões rodam sem as regras do Ataque e sem ponto de restauração, e a seção avisa em vermelho.

**Como cada CLI roda ▸**, recolhido em cada seção, traz as flags:

| | Defesa, na aba, tarefas que alteram arquivos | Ataque, missões na aba |
| --- | --- | --- |
| **Cursor** | `--force` | `--force --trust` |
| **Claude Code** | `--permission-mode acceptEdits` (comandos só os liberados nele, mais `git rm` de arquivos versionados, um a um, para a IA conseguir aplicar uma decisão de remover código) | `--permission-mode bypassPermissions` |
| **Codex** | `--dangerously-bypass-approvals-and-sandbox` (só no devcontainer) | `--dangerously-bypass-approvals-and-sandbox` |

Nas tarefas de leitura, nenhum CLI escreve. Commit, PR e Rádio do Ataque também são só leitura.

<a id="recursos"></a>

## Recursos

A aba **Recursos** é do Ataque e só tem conteúdo com ele ligado. Ela tem duas subabas:

- **Skills:** as skills achadas no projeto (`.claude/skills`, `.cursor/skills`, `.agents/skills`, `.codex/skills`) e
  na sua pasta pessoal, com nome, descrição do `SKILL.md` e de onde vêm. As do Spec Kit ficam de fora.
  - **Buscar** filtra pelo nome ou pela descrição; **Todas**, **No Lançador** e **Disponíveis** filtram pelo estado.
  - Cada skill tem **Adicionar** ou **Remover**. A que está no Lançador leva a etiqueta **✓ No Lançador**, e uma linha
    embaixo da busca confirma a última mudança. A skill adicionada aparece na aba **Skills extras** do Lançador.
  - Uma skill adicionada que não é mais achada continua na lista, com **Não encontrada**, para você poder removê-la.
  - **Pastas de skills:** **Adicionar Pasta de Skills…** aponta outra pasta; **Remover** tira a pasta da busca.
  - A escolha vale só para você, nesta IDE.
- **Spec Kit:** o `specify` desta máquina e os comandos instalados no projeto, com **Instalar a Integração**, **Verificar
  de Novo** e **Remover do Projeto** (pergunta antes se a `specs/` vai junto). Atualizar o `specify` fica por sua conta.
  Veja [Preparar o projeto](ataque.md#preparar-o-projeto).

<a id="execucoes"></a>

## Execuções

A aba **Execuções** lista as execuções recentes dos dois sistemas. Duplo clique abre o registro: o pedido, os passos, os
arquivos alterados e a resposta. Os registros ficam em:

- `ironhawk/iron-dome/ia/`: as tarefas da Defesa (e as skills extras). Cada execução é um arquivo só, com o pedido e a
  resposta. No terminal, a IA grava a resposta em `ironhawk/iron-dome/ia/respostas/`, e o plugin apaga esse arquivo
  depois de copiá-la para o registro.
- `ironhawk/tomahawk/execucoes/`: as missões do Ataque.

**A pasta não cresce sem fim.** Quando uma execução termina, os registros mais antigos saem sozinhos: ficam os 20 mais
novos de cada sistema, ou o número que você puser em **Registros guardados**, nas Settings. Dos pedidos que a IA lê
(`ia/pedidos/`), ficam os 10 últimos.

**Nada disso vai para o repositório.** O que o plugin grava em `ironhawk/` é de quem está trabalhando no projeto, nesta
máquina. Por isso ele cria um `ironhawk/.gitignore` que ignora a pasta inteira, sem mexer no `.gitignore` do projeto,
que é da equipe. Para compartilhar algo dessa pasta, edite esse arquivo: o plugin não o reescreve, nem vazio. Se o seu
projeto já tinha arquivos de `ironhawk/` commitados, o `.gitignore` não os tira do git: rode `git rm -r --cached ironhawk`
uma vez.

**Limpar Pasta do IronHawk**, na barra do Arsenal, mostra o que há em `ironhawk/` por categoria, com a quantidade e o
tamanho: marque a raiz para apagar tudo, uma categoria inteira ou arquivo por arquivo. Já vêm marcadas as categorias que
só guardam registros (execuções e pedidos da IA, e as conferências que o Escudo gravava antes de o Conferido sair); as
revisões encerradas do Radar, os PRs da Patrulha, o aprendizado da Inteligência e os critérios do Escudo começam
desmarcados, porque algum mecanismo os usa. A revisão
aberta do Radar, as proteções do Ataque e o `.gitignore` da pasta nunca aparecem, e a limpeza não abre com a IA rodando. O que sai fica no Local History da IDE.

O que chama a IA em cada lugar:

| Onde | Ação | Altera arquivos? |
| --- | --- | --- |
| Radar e editor | **Disparar com IA** | Sim |
| Radar | **Verificar Regras de IA**; as perguntas de *Discutir antes de alterar* | Não |
| Patrulha | **Revisar com IA**; **Respostas da IA** | Não |
| Escudo | **Explicar Falha com IA** (a resposta oferece **Corrigir com IA**, que altera) | Não |
| Lançador | As etapas do Spec Kit, as skills extras e o **Gerar com IA** da Entrega | Etapas e skills: sim |
| Rádio | **Responder com IA** | Não |
| Diagnóstico | **Analisar com IA** | Não |

<a id="settings"></a>

## Settings

Em **Settings > Tools > IronHawk IA**:

- **IronHawk IA:** liga e desliga a **Defesa** e o **Ataque** (pelo menos um fica ligado). Desligado, o sistema sai do
  painel e perde as ações e os avisos; nos editores abertos, ao reiniciar a IDE, que a página oferece na hora.
  - **Arsenal (IA):** a mesma configuração da aba IA, com **Salvar e Testar** e **Salvar e Testar no Terminal**, e
    **Onde rodar a IA**. Também ficam aqui **Registros guardados** (quantos registros de execução ficam em `ironhawk/`)
    e **Visual leve**, para máquinas mais fracas: tira a animação das barras de andamento, as barras de proporção das
    árvores e os destaques coloridos dos nomes. A informação é a mesma.
  - **Defesa: Inteligência da IA:** dossiê das classes, aprendizado do time e o aviso de comentários sobrepostos. Veja
    [Inteligência da IA](defesa.md#inteligencia-da-ia).
  - **Ataque: Spec Kit e Proteções:** as proteções das missões e o executável do `specify`.

<a id="diagnostico"></a>

## Diagnóstico

**Help > IronHawk IA: Diagnóstico**, também na engrenagem do painel, abre um diálogo com duas abas:

- **Compatibilidade:** a situação de cada mecanismo (OK, opcional, limitado ou indisponível) e o que fazer. O Escudo
  usa o plugin JUnit; a Patrulha, os plugins Git e GitHub;
  e o Arsenal, um CLI de IA. **Copiar Diagnóstico** copia tudo em Markdown.
- **Logs de erro:** os erros e avisos do plugin no `idea.log` (e no anterior), da Defesa, do Ataque e do painel, com o
  mecanismo, a origem no código e a pilha. Filtra por mecanismo e por nível. Ações: **Reportar Este Erro**, **Analisar com
  IA**, **Copiar Erro** e **Atualizar**; no ⋮, **Copiar Todos** e **Abrir idea.log**.

**Reportar Bug** fica no rodapé. Ao abrir o projeto, se algum mecanismo não vai funcionar, um aviso traz **Abrir
Diagnóstico** e **Não Avisar de Novo**.

<a id="reportar-bug"></a>

## Reportar Bug

**Help > Reportar Bug do IronHawk IA**, ou o botão da caixa-preta no título do painel, abre um formulário curto: um título
e um texto livre. O relatório é do **plugin**, não do seu projeto, e vira uma issue **pública** em
[main-io-tech/ironhawk-ia](https://github.com/main-io-tech/ironhawk-ia/issues).

- **Texto livre.** Se a IA estiver pronta, ela só organiza o seu texto em *o que aconteceu*, *passos* e *esperado*, sem
  ler o seu projeto nem acrescentar nada. Sem a IA, o texto segue como você escreveu.
- **Print e GIF sob demanda.** **Tirar print** esconde o formulário, espera 3 s e captura a janela da IDE. **Gravar GIF**
  grava a janela por até 30 s; **Parar Gravação**, na notificação, traz o formulário de volta. Nada vai sem você pedir.
- **Prévia antes de publicar.** A tela seguinte mostra exatamente o que será publicado (o texto, os erros do log, que
  podem trazer caminhos do seu projeto, e o ambiente) e deixa tirar os erros antes de **Publicar no GitHub**.
- **Conta do GitHub do IntelliJ** (**Settings > Version Control > GitHub**). Sem os plugins Git e GitHub, o plugin abre a
  página de nova issue no navegador, já preenchida.
- **Anexos.** A API do GitHub não anexa imagens: depois de criar a issue, o plugin abre a issue e a pasta com o print e o
  GIF, para você arrastar os arquivos para a página da issue.
- **Label.** O prefixo `[Bug]` no título é o que a GitHub Action do repositório usa para aplicar a label `bug`.

Quando o plugin lança uma exceção, a janela de erros da IDE ganha **Reportar ao IronHawk IA**, que abre o Reportar Bug com
o erro. O erro só fica marcado como enviado quando a issue é criada.
