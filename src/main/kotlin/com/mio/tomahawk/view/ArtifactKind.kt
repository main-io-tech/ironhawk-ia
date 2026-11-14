package com.mio.tomahawk.view

import com.mio.tomahawk.run.RunStore
import java.nio.file.Path

/** O tipo de um Markdown do Spec Kit ou do Tomahawk, pelo nome e pela pasta: decide o painel de resumo do visualizador. */
enum class ArtifactKind(val label: String, val emoji: String, val css: String) {
    SPEC("Especificação", "🎯", "spec"),
    PLAN("Plano de Voo", "🧭", "plan"),
    TASKS("Waypoints", "🛰️", "tasks"),
    CHECKLIST("Checklist de Pré-voo", "✅", "checklist"),
    CONSTITUTION("Regras de Engajamento", "📜", "constitution"),
    RESEARCH("Pesquisa", "🔬", "research"),
    DATA_MODEL("Modelo de Dados", "🧩", "datamodel"),
    QUICKSTART("Guia Rápido", "⚡", "quickstart"),
    CONTRACT("Contrato", "🔌", "contract"),
    RUN("Registro de Missão", "📡", "run"),
    INPUT("Pedido", "✉️", "input"),
    DOCUMENT("Documento", "📄", "doc"),
    ;

    companion object {
        fun of(file: Path): ArtifactKind {
            val name = file.fileName?.toString()?.lowercase().orEmpty()
            val path = file.toString().replace('\\', '/').lowercase()
            val parts = path.split('/')
            val parent = parts.getOrNull(parts.size - 2)
            val ours = path.startsWith("${RunStore.DIR}/") || "/${RunStore.DIR}/" in path
            return when {
                parent == RunStore.RUNS_DIR && ours -> RUN
                parent == RunStore.INPUTS_DIR && ours -> INPUT
                name == "constitution.md" -> CONSTITUTION
                parent == "checklists" -> CHECKLIST
                "contracts" in parts -> CONTRACT
                name == "spec.md" -> SPEC
                name == "plan.md" -> PLAN
                name == "tasks.md" -> TASKS
                name == "research.md" -> RESEARCH
                name == "data-model.md" -> DATA_MODEL
                name == "quickstart.md" -> QUICKSTART
                else -> DOCUMENT
            }
        }

        /**
         * O que o visualizador do Tomahawk abre: os Markdown de `specs/`, a constituição e os de `ironhawk/tomahawk/`, e os
         * contratos OpenAPI (YAML ou JSON) das pastas `contracts/`.
         */
        fun handles(relative: String): Boolean {
            val path = relative.replace('\\', '/')
            val lower = path.lowercase()
            if (path.startsWith("specs/") && "/contracts/" in lower && (lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json"))) return true
            if (!path.endsWith(".md", ignoreCase = true)) return false
            return path.startsWith("specs/") || path == ".specify/memory/constitution.md" || path.startsWith("${RunStore.DIR}/")
        }
    }
}
