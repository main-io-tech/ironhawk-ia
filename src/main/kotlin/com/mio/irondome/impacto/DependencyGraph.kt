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
