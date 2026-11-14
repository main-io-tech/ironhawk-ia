package com.mio.tomahawk.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.MechanismList
import com.mio.irondome.base.MechanismTree
import com.mio.ironhawk.ArsenalPanel
import com.mio.tomahawk.run.RunStatus
import com.mio.tomahawk.run.RunStore
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.run.Transcript
import com.mio.tomahawk.run.Verdict
import com.mio.tomahawk.speckit.SddStep
import com.mio.tomahawk.speckit.SpecKitService
import com.mio.tomahawk.speckit.StepStatus
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ListCellRenderer
import javax.swing.tree.DefaultMutableTreeNode

/** Monta as abas nativas do Ataque e o Arsenal sobre um projeto Spec Kit de exemplo. */
class NativeTabsTest : BasePlatformTestCase() {

    private fun buttons(root: Container): List<String> = UIUtil.findComponentsOfType(root as JComponent, JButton::class.java).map { it.text }

    private fun renderAll(list: MechanismList<*>) {
        list.setSize(400, 600)
        @Suppress("UNCHECKED_CAST")
        val renderer = list.cellRenderer as ListCellRenderer<Any?>
        (0 until list.model.size).forEach { index ->
            assertNotNull(renderer.getListCellRendererComponent(list, list.model.getElementAt(index), index, false, false))
        }
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun sampleProject(root: Path) {
        write(root, ".specify/integration.json", """{"default_integration":"cursor-agent","integration_settings":{"cursor-agent":{"invoke_separator":"-"}}}""")
        write(root, ".specify/memory/constitution.md", "# Photo Constitution\n\n## Core Principles\n\n### I. Testes primeiro\nTodo código nasce de um teste.\n")
        SddStep.entries.forEach { write(root, ".cursor/skills/speckit-${it.command}/SKILL.md", "# ${it.command}") }
        write(root, ".specify/feature.json", """{"feature_directory":"specs/002-photo-albums"}""")
        write(root, "specs/001-login/spec.md", "# Feature Specification: Login\n\n### User Story 1 - Entrar (Priority: P1)\n\n- **FR-001**: entrar\n")
        val dir = "specs/002-photo-albums"
        write(root, "$dir/spec.md", """
            # Feature Specification: Álbuns de Fotos

            **Status**: Draft

            ## User Scenarios & Testing

            ### User Story 1 - Organizar fotos em álbuns (Priority: P1)

            ### User Story 2 - Reordenar álbuns (Priority: P2)

            ### Edge Cases

            - Foto sem data? [NEEDS CLARIFICATION: usar a data do arquivo?]

            ## Requirements

            - **FR-001**: agrupar por data
            - **FR-002**: arrastar e soltar
            - **SC-001**: encontrar um álbum em 10 s
        """.trimIndent())
        write(root, "$dir/plan.md", "# Implementation Plan\n\n## Technical Context\n\n**Language/Version**: TypeScript 5.6\n**Primary Dependencies**: Fastify, React\n**Storage**: PostgreSQL\n")
        write(root, "$dir/data-model.md", "# Data Model\n\n### Album\n\n| Field | Type | Constraints |\n|---|---|---|\n| id | UUID | PK |\n| title | string | required |\n\n### Photo\n\n- **id**: UUID (primary key)\n- **album_id**: UUID, foreign key → Album\n")
        write(root, "$dir/research.md", "# Research\n")
        write(root, "$dir/tasks.md", """
            # Tasks

            ## Phase 1: Setup

            - [x] T001 Create project structure
            - [x] T002 [P] Configure linting

            ## Phase 2: Foundational

            - [x] T003 Setup database schema
            - [ ] T004 [P] Create base models

            ## Phase 3: User Story 1 - Organizar fotos em álbuns (Priority: P1)

            - [ ] T005 [P] [US1] Contract test for POST /albums
            - [ ] T006 [US1] Implement AlbumService in src/services/album.ts
        """.trimIndent())
        write(root, "$dir/checklists/ux.md", "# UX\n\n## Clareza\n\n- [x] CHK001 Estados definidos? [Completeness]\n- [ ] CHK002 Soltar fora? [Gap]\n")
        val now = System.currentTimeMillis()
        listOf(
            Triple(SddStep.SPECIFY, RunStatus.OK, 95L),
            Triple(SddStep.PLAN, RunStatus.OK, 240L),
            Triple(SddStep.TASKS, RunStatus.FAILED, 30L),
            Triple(SddStep.TASKS, RunStatus.OK, 180L),
            Triple(SddStep.CLARIFY, RunStatus.TERMINAL, 0L),
            Triple(SddStep.IMPLEMENT, RunStatus.OK, 620L),
            Triple(SddStep.CONVERGE, RunStatus.OK, 140L),
        ).forEachIndexed { i, (step, status, seconds) ->
            val entry = Transcript.Entry(
                step, step.invocation(), dir, status, if (status == RunStatus.FAILED) "O Cursor terminou com o código 1." else null,
                if (step == SddStep.CONVERGE) Verdict.TASKS_APPENDED else null, now - (7 - i) * 3_600_000L, seconds, "Cursor", "gpt-5",
                "", "p", if (status == RunStatus.OK) listOf("a.ts", "b.ts") else emptyList(), emptyList(), "ok", "", listOf("agent"),
            )
            write(root, "${RunStore.DIR}/${RunStore.RUNS_DIR}/2026110${i}-0900${i}0-${step.command}.md", Transcript.render(entry))
        }
    }

    fun testTheLauncherPutsTheConstitutionAboveTheEightFeatureSteps() {
        val root = Path.of(project.basePath!!)
        sampleProject(root)
        SpecKitService.getInstance(project).refreshNow()
        TomahawkSettings.launch()?.let { TomahawkSettings.markVerified("04/11/2026 14:00") }
        val panel = LauncherPanel(project)
        Disposer.register(testRootDisposable, panel)
        val list = UIUtil.findComponentOfType(panel, MechanismList::class.java)!!
        val steps = (0 until list.model.size).map { index ->
            val row = list.model.getElementAt(index)!!
            row.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(row) as StepStatus
        }
        assertEquals(SddStep.CONSTITUTION, steps.first().step)
        assertEquals(8, steps.drop(1).size)
        assertFalse(steps.drop(1).any { it.step == SddStep.CONSTITUTION })
        renderAll(list)
        assertTrue(buttons(panel).contains("Lançar"))
    }

    fun testTheNativeTelemetryListsTheRunsByDay() {
        val root = Path.of(project.basePath!!)
        sampleProject(root)
        SpecKitService.getInstance(project).refreshNow()
        val panel = TelemetryPanel(project)
        Disposer.register(testRootDisposable, panel)
        val list = UIUtil.findComponentOfType(panel, MechanismList::class.java)!!
        assertEquals(SpecKitService.getInstance(project).snapshot.runs.size, list.model.size)
        assertTrue(list.model.size > 0)
        renderAll(list)
    }

    fun testTheEvaluationShowsTheConstitutionAndTheActiveFeature() {
        val root = Path.of(project.basePath!!)
        sampleProject(root)
        SpecKitService.getInstance(project).refreshNow()
        val panel = EvaluationPanel(project)
        Disposer.register(testRootDisposable, panel)
        val tree = UIUtil.findComponentOfType(panel, MechanismTree::class.java)!!
        val rootNode = tree.model.root as DefaultMutableTreeNode
        assertEquals(2, rootNode.childCount)
    }

    fun testTheArsenalHasTheAiAndTheSpecKit() {
        val root = Path.of(project.basePath!!)
        sampleProject(root)
        SpecKitService.getInstance(project).refreshNow()
        val panel = ArsenalPanel(project)
        Disposer.register(testRootDisposable, panel)
        val labels = buttons(panel)
        assertTrue(labels.toString(), "Salvar e Testar" in labels)
        assertTrue(labels.toString(), "Remover do Projeto" in labels)
    }

    fun testTheLauncherBeforeTheSpecKitOffersToInitialize() {
        val root = Path.of(project.basePath!!)
        root.resolve(".specify").toFile().deleteRecursively()
        SpecKitService.getInstance(project).refreshNow()
        val panel = LauncherPanel(project)
        Disposer.register(testRootDisposable, panel)
        assertTrue(UIUtil.findComponentOfType(panel, SpecKitSetupPanel::class.java)!!.isVisible)
        assertTrue(buttons(panel).contains("Inicializar o Spec Kit"))
        assertEquals(0, UIUtil.findComponentOfType(panel, MechanismList::class.java)!!.model.size)
    }
}
