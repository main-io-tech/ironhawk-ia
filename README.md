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
  <img src="https://img.shields.io/badge/funciona%20com-Cursor%20%C2%B7%20Claude%20Code-8250DF" alt="Funciona com Cursor e Claude Code">
  <img src="https://img.shields.io/badge/vers%C3%A3o-0.1.0-0969DA" alt="Versão 0.1.0">
</p>

O IronHawk IA junta dois plugins num só. Cada sistema pode ser ligado ou desligado em
**Settings > Tools > IronHawk IA**, e os dois podem ficar ligados ao mesmo tempo.

| Sistema | O que faz |
|---|---|
| 🛡️ **Defesa: Iron Dome** | Revisão do código com a IA: você marca os alvos no editor e a IA intercepta as correções. Radar, Centro de Comando, Raio de Impacto, Sentinela, Escudo, Patrulha e Inteligência. |
| 🚀 **Ataque: Tomahawk** | Desenvolvimento guiado pela spec com o [Spec Kit](https://github.com/github/spec-kit): a IA escreve a feature etapa por etapa. Lançador, Extração, Rádio, Avaliação e Telemetria. |

Os dois ficam num painel só, **IronHawk IA**. No título dele, **Defesa** e **Ataque** trocam as abas do sistema, e o
**Arsenal** fica sempre no fim: a configuração da IA (o CLI, onde ele está, o modelo, a chave e o teste) é uma só para
os dois, com o que é de cada sistema logo abaixo e as execuções recentes dos dois.

Desligado, o sistema sai do painel e perde as ações e os avisos. O que já está nos editores abertos sai ao reiniciar a
IDE, e a página de configurações oferece reiniciar na hora.

## Documentação

- [Defesa: Iron Dome](docs/iron-dome.md)

## Instalação

1. Baixe o `.zip` do plugin na página de releases do repositório. Não use o *Source code (zip)*.
2. No IntelliJ IDEA 2025.2 ou mais novo: **Settings > Plugins > ⚙️ > Install Plugin from Disk...** e escolha o
   arquivo, sem descompactar.
3. Reinicie a IDE.

O IronHawk substitui o Iron Dome IA e o Tomahawk IA: desinstale os dois antes, porque eles não rodam junto com ele.
As configurações dos dois continuam valendo no IronHawk. A IA configurada no Iron Dome vira a do Arsenal; quem só tinha
configurado a IA no Tomahawk tem essa configuração levada para o Arsenal na primeira abertura, e basta testar de novo.

## Build

```bash
./gradlew buildPlugin    # roda os testes e gera build/distributions/ironhawk-ia-<versão>.zip
./gradlew verifyPlugin   # Plugin Verifier contra as IDEs de gradle.properties
```
