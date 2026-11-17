# Escudo: critérios de aceite e padrão dos testes

> **Estado: implementado.** O Escudo lê o catálogo, organiza a árvore por critério e tem as ações de IA. A lógica
> (leitura do catálogo, tags, status e XML do Cucumber) tem testes. O Cucumber entra pelo runner do JUnit ou pelo XML do
> `junit:` do CLI (veja [Cucumber](#cucumber-opcional)). A aba está descrita em
> [Defesa](defesa.md#rodar-e-padronizar-testes).

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
  exige:                         # a lista também vale em bloco, um item por linha
    - unidade
    - aceitacao
```

| Campo | Obrigatório | Regra |
|---|---|---|
| `id` | sim | Único em todos os arquivos. Sem espaços e sem `, ( ) & | !` (limites das tags do JUnit). Ex.: `ROT-03` |
| `titulo` | sim | Uma frase que diga o que deve ser verdade |
| `grupo` | não | Agrupamento de negócio na aba; sem ele, o nome do arquivo |
| `origem` | não | De onde veio. O Escudo só mostra, nunca consulta |
| `exige` | não | Níveis que precisam cobrir: `unidade`, `integracao`, `aceitacao`, em linha (`[a, b]`) ou em lista de bloco (um `- item` por linha). Omitido, vale qualquer nível |

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
`[Chave]` no início do `@DisplayName` não é mais usada nem cobrada: o agrupamento vem da `@Tag` ou do `grupo` do
critério. **Padronizar Testes** (veja [Ações de IA](#ações-de-ia)) leva os testes a este padrão.

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

**Cucumber fora do runner (CLI).** Quando o Cucumber roda por `io.cucumber.core.cli.Main` (por exemplo, no
`exec-maven-plugin`) com `--plugin junit:target/.../TEST-cucumber.xml`, o Escudo lê esse XML (qualquer `*cucumber*.xml`
em `target`, `build` ou `out`). Os cenários entram nos **mesmos agrupamentos** dos testes JUnit (por tag ou por
critério), com a pílula **Cucumber**. As tags vêm dos `.feature` dos fontes, casando funcionalidade e nome do cenário; as
da funcionalidade valem para todos os cenários dela. Assim `@criterio:ROT-03 @nivel:aceitacao` conta como cobertura, com
o resultado do último XML.

- O XML é relido ao abrir a aba, quando ela volta a aparecer e em **Recarregar Critérios e Cucumber** (no ⋮). Rode o
  Cucumber (`mvn verify`) e recarregue; **Executar Todos os Testes** só roda o JUnit.
- É o resultado da última execução do Cucumber, não da atual. O subtítulo mostra a idade do XML, como *"Cucumber (XML
  há 5 min): 12 cenários, 1 falhando"*, e os detalhes de cada cenário dizem quando o XML foi gravado.
- Os cenários do XML **não mudam o veredito** da execução (*Escudo íntegro* ou *rompido*): ele conta só o JUnit rodado
  pela aba.
- Um cenário que aparece em vários XMLs entra uma vez só, com o resultado do XML mais novo.
- Cenários de Esquema do Cenário casam pelo nome; as tags de `Exemplos:` não são lidas.

Quando existem `.feature` nos fontes de teste, o Escudo lê também as tags deles para saber a **cobertura** sem precisar
rodar: um critério citado ali, mas sem execução, fica "não executado" em vez de "sem teste".

**Pelo engine do JUnit Platform:** o Escudo resolve as tags de um teste pelo código Java do JUnit e ainda **não** lê as
tags do `.feature` nessa execução ao vivo: o cenário aparece em **Sem tag**, na organização por tag, e em **Sem
critério**. Para ter as tags hoje, use o modo CLI com o XML acima.

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

Um menu só, **Organizar por**, na barra da aba:

- **Critério** (padrão quando o projeto tem catálogo): `Grupo > Critério > testes que o cobrem`, com o status de cada
  critério, os níveis que o cobrem e um placar no cabeçalho ("18 de 24 atendidos, 3 sem teste"). Os testes sem critério
  ficam em **Sem critério**, os de `criterio:nenhum` em **Critério não se aplica**, e as tags órfãs em **Tags de critério
  desconhecido**.
- **Tag** (a única sem catálogo): a árvore por `@Tag` do JUnit, com os testes sem tag em **Sem tag**.
- Um teste que cobre dois critérios, ou tem duas tags, aparece nos dois grupos.
- Os cenários do Cucumber lidos do XML entram nas duas organizações, com a pílula **Cucumber**.

## Ações de IA

As duas só tocam em arquivos de critério e em anotações dos testes, nunca na lógica deles. Quando a IA escreve direto,
o aviso do fim traz **Desfazer Alterações da IA**. O catálogo é relido ao abrir a aba e em **Recarregar Critérios e
Cucumber**, no ⋮.

1. **Gerar Critérios com IA** (no ⋮). A fonte pode ser um texto colado, um arquivo (`spec.md`, PRD) ou um identificador
   que a IA consulte pelo MCP do Linear ou do Jira. A IA grava `ironhawk/iron-dome/criterios/*.yaml` neste contrato,
   reaproveitando os IDs que já existem.
2. **Padronizar Testes** (na barra). Uma ação só, que muda com o catálogo:
   - **Com catálogo**, a IA liga os testes aos critérios, direto, sem passar pelo Radar: acrescenta `@Tag("criterio:…")`
     e, se houver como saber, `@Tag("nivel:…")`, deixa o `@DisplayName` em Dado/Quando/Então e põe um `@DisplayName` na
     classe de teste que não tem. Nos `.feature`, põe `@criterio:ID` acima do cenário. Quando não houver como saber a
     qual critério um teste se liga, usa `criterio:nenhum` (teste técnico) ou deixa sem tag e lista na resposta, em vez
     de chutar.
   - **Sem catálogo**, marca no Radar as classes de teste sem `@DisplayName`, como alvos **Marcados pelo Escudo**.
     Disparar esses alvos faz a IA corrigir.

Escrever os testes que faltam para os critérios "sem teste" está **fora** deste contrato.
