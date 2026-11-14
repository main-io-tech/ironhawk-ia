package com.mio.irondome.impacto

import com.intellij.openapi.vfs.VirtualFile

enum class ClassKind(val label: String) {
    CLASS("classe"),
    ABSTRACT("abstrata"),
    INTERFACE("interface"),
    ENUM("enum"),
    ANNOTATION("anotação"),
}

/**
 * Como duas classes se ligam, nos termos do diagrama de classes: [EXTENDS] é a generalização, [IMPLEMENTS] a
 * realização de uma interface, [ASSOCIATION] um atributo do tipo da outra classe e [USAGE] qualquer outra
 * dependência (chamada, construtor, membro estático).
 */
enum class EdgeKind { EXTENDS, IMPLEMENTS, ASSOCIATION, USAGE }

/** Quanto do projeto pode quebrar se a classe mudar, medido pelas classes que dependem dela. */
enum class ChangeRisk(val label: String) {
    LOW("Baixo"),
    MEDIUM("Médio"),
    HIGH("Alto");

    override fun toString() = label

    companion object {
        const val MEDIUM_FROM = 3
        const val HIGH_FROM = 8

        /** Estar num ciclo sobe o risco um nível. */
        fun of(afferent: Int, inCycle: Boolean): ChangeRisk {
            val base = when {
                afferent >= HIGH_FROM -> HIGH
                afferent >= MEDIUM_FROM -> MEDIUM
                else -> LOW
            }
            return if (inCycle) entries[minOf(base.ordinal + 1, HIGH.ordinal)] else base
        }
    }
}

enum class TestCoverage(val label: String) {
    NONE("Sem testes"),
    PARTIAL("Parcial"),
    GOOD("Bem coberta"),
    /** A própria classe é um teste, então a métrica não se aplica. */
    TEST_CLASS("Classe de teste"),
}

/**
 * [afferent]: classes de produção que dependem desta. [efferent]: classes de produção de que esta
 * depende. Contam o projeto inteiro, não só as classes do diagrama. [attributes] e [operations] são os
 * membros já formatados como num diagrama de classes (`- nome: Tipo`, `+ nome(Tipo): Retorno`).
 */
class ClassNode(
    val qualifiedName: String,
    val name: String,
    val packageName: String,
    val kind: ClassKind,
    val file: VirtualFile?,
    val filePath: String,
    /** Distância até a classe analisada: 0 é ela própria. */
    val level: Int,
    val afferent: Int,
    val efferent: Int,
    val attributes: List<String> = emptyList(),
    val operations: List<String> = emptyList(),
    /** Quantos membros além dos mostrados em [attributes] e [operations]. */
    val hiddenAttributes: Int = 0,
    val hiddenOperations: Int = 0,
)

class Edge(val from: String, val to: String, val kind: EdgeKind)

class DependencyGraph(
    val focus: String,
    val depth: Int,
    val nodes: List<ClassNode>,
    val edges: List<Edge>,
    /** O limite de classes foi atingido e parte dos vizinhos ficou de fora. */
    val truncated: Boolean,
)
