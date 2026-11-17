<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="110" alt="IronHawk IA">
</p>

<h1 align="center">IronHawk IA</h1>

<p align="center">
  <b>Defesa e ataque para o seu código com IA, direto no IntelliJ.<br>
  A resistência do ferro na defesa, a precisão do gavião no ataque.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/IntelliJ%20IDEA-2025.2%2B-000000?logo=intellijidea&logoColor=white" alt="IntelliJ IDEA 2025.2+">
  <img src="https://img.shields.io/badge/funciona%20com-Cursor%20%C2%B7%20Claude%20Code%20%C2%B7%20Codex-8250DF" alt="Funciona com Cursor, Claude Code e Codex">
  <img src="https://img.shields.io/badge/vers%C3%A3o-0.2.0-0969DA" alt="Versão 0.2.0">
</p>

O IronHawk IA junta dois plugins num só. Cada sistema pode ser ligado ou desligado em
**Settings > Tools > IronHawk IA**, e os dois podem ficar ligados ao mesmo tempo.

| Sistema | O que faz |
|---|---|
| 🛡️ **Defesa: Iron Dome** | Revisão do código com a IA: você marca os alvos no editor, usa **Disparar com IA** e a IA intercepta as correções. Abas Radar, Sentinela, Raio de Impacto, Escudo e Patrulha. |
| 🚀 **Ataque: Tomahawk** | Desenvolvimento guiado pela spec com o [Spec Kit](https://github.com/github/spec-kit): a IA escreve a feature etapa por etapa. Abas Lançador (com a Entrega: commit, push e PR), Avaliação, Telemetria e Rádio. |

Os dois ficam num painel só, **IronHawk IA**. No título dele, **Defesa** e **Ataque** trocam as abas do sistema, e o
**Arsenal** fica sempre no fim, o mesmo para os dois: a configuração da IA (o CLI, onde ele está, o modelo, a chave e o
teste), onde rodar a IA, a segurança, as skills extras, o Spec Kit e as execuções recentes. O que vale só para um sistema
leva a etiqueta Defesa ou Ataque.

A IA roda pelo CLI do **Cursor**, do **Claude Code** ou do **Codex**, na aba (no devcontainer do projeto) ou no terminal
da IDE, e pelo do **Antigravity**, só no terminal. Problemas com o plugin: **Help > IronHawk IA: Diagnóstico** e
**Help > Reportar Bug do IronHawk IA**.

Desligado, o sistema sai do painel e perde as ações e os avisos. O que já está nos editores abertos sai ao reiniciar a
IDE, e a página de configurações oferece reiniciar na hora.

## Documentação

- [Defesa: Iron Dome](docs/defesa.md): marcar e disparar alvos, ver o que mudou, medir o impacto, rodar e padronizar
  testes, revisar um PR.
- [Ataque: Tomahawk](docs/ataque.md): preparar o projeto, lançar as etapas, entregar num PR, revisar o que foi gerado,
  acompanhar a missão e responder aos comentários dos seus PRs.
- [Arsenal](docs/arsenal.md): a IA comum aos dois sistemas, onde ela roda, a segurança, as Settings, o Diagnóstico e o
  Reportar Bug.
- [Escudo: critérios de aceite](docs/escudo-criterios.md): o contrato que liga os testes aos critérios.

## Instalação

1. Baixe o `.zip` do plugin na página de releases do repositório. Não use o *Source code (zip)*.
2. No IntelliJ IDEA 2025.2 ou mais novo: **Settings > Plugins > ⚙️ > Install Plugin from Disk...** e escolha o
   arquivo, sem descompactar.
3. Reinicie a IDE.

O IronHawk substitui o Iron Dome IA e o Tomahawk IA: desinstale os dois antes, porque eles não rodam junto com ele.
As configurações dos dois continuam valendo no IronHawk. A IA configurada no Iron Dome vira a do Arsenal; quem só tinha
configurado a IA no Tomahawk tem essa configuração levada para o Arsenal na primeira abertura, e basta testar de novo.
Onde rodar a IA também virou uma escolha só: vale a da Defesa, e a do Tomahawk só se a da Defesa nunca saiu do padrão.

## Build

```bash
./gradlew buildPlugin    # roda os testes e gera build/distributions/ironhawk-ia-<versão>.zip
./gradlew verifyPlugin   # Plugin Verifier contra as IDEs de gradle.properties
```
