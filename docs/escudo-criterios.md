# Escudo: critérios de aceite e padrão dos testes

> **Estado: implementado, ainda não visto na IDE.** O Escudo lê o catálogo, mostra a visão "Por critério" e tem as duas
> ações de IA. A lógica (leitura do catálogo, tags e status) tem testes; o desenho da aba e as ações de IA não foram
> conferidos na IDE. Um teste de Cucumber só entra no Escudo se a tag dele chegar pelo runner do JUnit (veja
> [Cucumber](#cucumber-opcional)).

O objetivo é responder, olhando o Escudo, **se os critérios de aceite estão sendo atendidos**, em qualquer projeto JUnit 5
e de qualquer empresa. Por isso o contrato é neutro: não depende de Linear, Jira, Spec Kit nem de Cucumber. Quem tiver
outra fonte pede para a IA adaptá-la a este contrato.

## O contrato em três peças

| Peça | Onde fica | O que diz |
|---|---|---|
| **Catálogo de critérios** | `ironhawk/iron-dome/criterios/*.yaml` | Quais critérios existem, com ID único |
| **Tag `criterio:`** | no teste (`@Tag` do JUnit) | Qual critério o teste cobre |
| **Tag `nivel:`** (opcional) | no teste | Em que nível o teste prova o critério |

Só os prefixos `criterio:` e `nivel:` têm significado para o Escudo. Qualquer outra tag (`lento`, `smoke`) continua livre
e vira filtro na aba.

### 1. Catálogo

Um YAML por assunto, na pasta do Iron Dome, que o Escudo lê em cada projeto vigiado. O ID é único no conjunto.

```yaml
# ironhawk/iron-dome/criterios/roteiro.yaml
- id: ROT-03
  titulo: Mover atividade para um dia que não existe é recusado e o roteiro não muda
  grupo: Roteiro                 # opcional; agrupa na aba
  origem: MAI-184                # opcional; texto livre (issue, spec, PRD)
  exige: [aceitacao]             # opcional; omitido = qualquer nível

- id: ROT-04
  titulo: Reposicionar uma atividade dentro do mesmo dia mantém as demais na ordem
  grupo: Roteiro
```

| Campo | Obrigatório | Regra |
|---|---|---|
| `id` | sim | Único em todos os arquivos. Sem espaços e sem `, ( ) & | !` (limites das tags do JUnit). Ex.: `ROT-03` |
| `titulo` | sim | Uma frase que diga o que deve ser verdade |
| `grupo` | não | Agrupamento de negócio na aba; sem ele, o nome do arquivo |
| `origem` | não | De onde veio. O Escudo só mostra, nunca consulta |
| `exige` | não | Níveis que precisam cobrir: `unidade`, `integracao`, `aceitacao`. Omitido, vale qualquer nível |

ID repetido, arquivo inválido ou campo desconhecido aparecem como erro de contrato, com arquivo e linha.

### 2. Tag `criterio:`

Um teste pode cobrir vários critérios, com uma tag por critério. Um teste que não se aplica a critério algum (utilitário
técnico) declara isso com `criterio:nenhum`, para o Escudo distinguir "esqueci de ligar" de "não se aplica".

```java
@Test
@Tag("criterio:ROT-03")
@Tag("nivel:unidade")
@DisplayName("""
        DADO um roteiro com um dia, \
        QUANDO move a atividade para um dia ausente, \
        ENTÃO recusa com DIA_DESTINO_INEXISTENTE e o roteiro permanece igual""")
void recusaDiaDestinoInexistente() { ... }
```

### 3. Tag `nivel:` (opcional)

`nivel:unidade`, `nivel:integracao` ou `nivel:aceitacao`. Sem a tag, o nível aparece como "não informado" e o teste conta
como cobertura. O Escudo **não infere** o nível pelo nome, pela pasta nem pelo framework.

## O teste "no padrão"

| Elemento | Padrão completo | Se faltar |
|---|---|---|
| `@DisplayName` | Dado/Quando/Então (ou Given/When/Then), com `->` para o resultado esperado quando couber | O teste roda e aparece com o nome do método |
| `@Tag("criterio:…")` | Uma por critério coberto, ou `criterio:nenhum` | Aparece em "Sem critério" |
| `@Tag("nivel:…")` | Opcional | "Nível não informado" |

Fora do padrão, o teste **roda normalmente e aparece nos executados**. O padrão completo só melhora a visão. A chave
`[Chave]` no início do `@DisplayName` deixa de ser usada: o agrupamento vem do `grupo` do critério. Com um catálogo no
projeto, o Escudo para de cobrar a chave (some o aviso e o botão Deixar no Padrão). Sem catálogo, a convenção da chave
continua como era.

## Cucumber (opcional)

Projetos sem Cucumber ignoram esta seção. Quem usa Cucumber deve rodá-lo como **engine do JUnit Platform**
(`cucumber-junit-platform-engine`), para os cenários aparecerem no mesmo runner e na mesma árvore dos demais testes. As
tags do Gherkin viram tags do JUnit, então o contrato é o mesmo:

```gherkin
# language: pt
Funcionalidade: Mover atividade para outro dia do roteiro

  @criterio:ROT-03 @nivel:aceitacao
  Cenário: Tentar mover uma atividade para um dia ausente
    Dado que o primeiro dia tem uma visita ao Coliseu
    Quando Ana tenta mover a visita para um dia que não faz parte do roteiro
    Então o aplicativo informa que o dia de destino não existe
    E o roteiro permanece como estava antes da tentativa
```

O Cucumber rodando por CLI fora do runner (como `io.cucumber.core.cli.Main`) não aparece no Escudo, que não lê o XML dele.
Quando existem `.feature` nos fontes de teste, o Escudo lê também as tags deles para saber a **cobertura** sem precisar
rodar: um critério citado ali, mas sem execução, fica "não executado" em vez de "sem teste".

**Limite atual:** o Escudo resolve as tags de um teste pelo código Java do JUnit. Para um cenário do Cucumber que chegue
pelo engine, ele ainda **não** lê as tags do `.feature` na execução: o cenário aparece na visão por execução e na lista
"Sem critério", e o critério dele fica "não executado". Falta ligar o resultado do cenário ao arquivo `.feature`.

## Status de um critério

| Status | Quando |
|---|---|
| **Atendido** | Tem teste, todos passaram e os níveis de `exige` (se houver) estão cobertos |
| **Parcial** | Os testes passaram, mas falta um nível de `exige` (ex.: só unidade, e o critério exige aceitação) |
| **Falhando** | Algum teste que o cobre falhou |
| **Não executado** | Tem teste, mas ele não rodou nesta execução |
| **Sem teste** | Nenhum teste ou cenário aponta para ele |

Teste ignorado (`@Disabled`) não conta como cobertura. Uma tag `criterio:` que aponta para um ID que não existe é mostrada
como **órfã**.

## A visão no Escudo

- **Por critério** (padrão quando o projeto tem catálogo): `Grupo > Critério > testes que o cobrem`, com o status de cada
  critério, os níveis que o cobrem e um placar no topo ("18 de 24 atendidos, 3 sem teste").
- **Por execução** (padrão sem catálogo, como é hoje): a árvore dos testes executados, com o selo do critério em cada um.
  Testes sem critério ficam em "Sem critério" na visão por critério.
- Um teste que cobre dois critérios aparece nos dois grupos.

## Ações de IA

Ambas ficam na barra da aba, só tocam em arquivos de critério e em anotações dos testes, nunca na lógica deles, e rodam
como qualquer ação de IA que escreve: o aviso do fim traz **Desfazer Alterações da IA**. A barra também tem **Recarregar
Critérios**, e o catálogo é relido ao abrir a aba e a cada execução.

1. **Gerar Critérios com IA.** A fonte pode ser um texto colado, um arquivo (`spec.md`, PRD) ou um identificador que a IA consulte
   pelo MCP do Linear ou do Jira. A IA grava `ironhawk/iron-dome/criterios/*.yaml` neste contrato, reaproveitando os IDs
   que já existem.
2. **Ligar Testes aos Critérios.** A IA lê o catálogo e os testes existentes, acrescenta `@Tag("criterio:…")` e, se houver como
   saber, `@Tag("nivel:…")`, e ajusta o `@DisplayName` para Dado/Quando/Então. O botão é **Ligar Testes aos
   Critérios** e só aparece com catálogo. A IA roda direto, sem passar pelo Radar. Quando não houver como saber a qual critério um teste se liga, ela usa `criterio:nenhum`
   em vez de chutar.

Escrever os testes que faltam para os critérios "sem teste" está **fora** deste contrato.
