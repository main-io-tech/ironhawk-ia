package com.mio.ironhawk

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.mio.irondome.base.rootPath
import com.mio.irondome.interceptor.AgentRunner
import com.mio.irondome.radar.ReviewCommentService
import com.mio.tomahawk.run.MissionRunner
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

/**
 * Escolhe o que apagar de `ironhawk/`: a raiz marca tudo, cada categoria marca os arquivos dela, e dá para marcar arquivo
 * por arquivo. A revisão aberta do Radar e as proteções do Ataque nunca aparecem.
 */
class IronHawkCleanupDialog(project: Project, groups: List<CleanupGroup>) : DialogWrapper(project) {

    private val rootNode = CheckedTreeNode(ROOT_LABEL)
    private val tree: CheckboxTree
    private val summary = JBLabel()

    init {
        groups.forEach { group ->
            val categoryNode = CheckedTreeNode(group)
            group.files.forEach { categoryNode.add(CheckedTreeNode(it).apply { isChecked = group.category.defaultSelected }) }
            categoryNode.isChecked = group.category.defaultSelected
            rootNode.add(categoryNode)
        }
        tree = CheckboxTree(Renderer(), rootNode).apply {
            isRootVisible = true
            model = DefaultTreeModel(rootNode)
            addCheckboxTreeListener(object : com.intellij.ui.CheckboxTreeListener {
                override fun nodeStateChanged(node: CheckedTreeNode) = updateSummary()
            })
        }
        TreeUtil.expand(tree, 1)
        title = "Limpar Pasta do IronHawk"
        setOKButtonText("Apagar Selecionados")
        init()
        updateSummary()
    }

    /** Os arquivos marcados. */
    fun selected(): List<CleanupFile> = tree.getCheckedNodes(CleanupFile::class.java, null).toList()

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel(
            "<html>Marque o que apagar. As categorias marcadas de início só guardam registros; as outras guardam dados que algum " +
                "mecanismo usa. A revisão aberta do Radar e as proteções do Ataque não aparecem aqui.</html>",
        ), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree).apply { preferredSize = Dimension(JBUI.scale(620), JBUI.scale(380)) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                add(ActionLink("Marcar tudo") { setAll(true) })
                add(ActionLink("Desmarcar tudo") { setAll(false) })
                add(ActionLink("Só os registros") { setDefaults() })
            }, BorderLayout.WEST)
            add(summary, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    private fun setAll(checked: Boolean) {
        tree.setNodeState(rootNode, checked)
        tree.repaint()
        updateSummary()
    }

    private fun setDefaults() {
        rootNode.children().toList().filterIsInstance<CheckedTreeNode>().forEach { node ->
            tree.setNodeState(node, (node.userObject as CleanupGroup).category.defaultSelected)
        }
        tree.repaint()
        updateSummary()
    }

    private fun updateSummary() {
        val files = selected()
        summary.text = if (files.isEmpty()) "Nada marcado"
        else "${files.size} ${if (files.size == 1) "arquivo" else "arquivos"} · ${IronHawkCleanup.formatSize(files.sumOf { it.size })}"
        isOKActionEnabled = files.isNotEmpty()
    }

    private class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? CheckedTreeNode ?: return
            when (val item = node.userObject) {
                is CleanupGroup -> {
                    textRenderer.append(item.category.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    textRenderer.append(
                        "  ${item.files.size} · ${IronHawkCleanup.formatSize(item.size)} · ${item.category.description}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
                is CleanupFile -> {
                    textRenderer.append(item.relative.substringAfterLast('/'))
                    textRenderer.append(
                        "  ${item.relative.substringBeforeLast('/', "")} · ${IronHawkCleanup.formatSize(item.size)}",
                        SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                    )
                }
                else -> textRenderer.append(item.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }

    companion object {
        private const val ROOT_LABEL = "${IronHawkFolder.ROOT}/ (tudo)"
        private val LOG = logger<IronHawkCleanupDialog>()

        /**
         * Abre a escolha e apaga o que o usuário marcar, pela VFS, para a exclusão ficar no Local History da IDE. Não
         * abre com a IA rodando, que pode estar gravando ali. Devolve se apagou algo.
         */
        fun show(project: Project): Boolean {
            val root = project.rootPath()?.let { Path.of(it) } ?: return false
            if (AgentRunner.getInstance(project).running != null || MissionRunner.getInstance(project).progress != null) {
                Messages.showInfoMessage(project, "Espere a IA terminar o que está rodando: ela pode estar gravando na pasta ${IronHawkFolder.ROOT}/.", "Limpar Pasta do IronHawk")
                return false
            }
            val reviews = project.service<ReviewCommentService>()
            val keep = reviews.savedReviews().filter { it.first == reviews.activeReviewId }.map { it.second }.toSet()
            val groups = IronHawkCleanup.scan(root, keep)
            if (groups.isEmpty()) {
                Messages.showInfoMessage(project, "Não há nada para limpar em ${IronHawkFolder.ROOT}/.", "Limpar Pasta do IronHawk")
                return false
            }
            val dialog = IronHawkCleanupDialog(project, groups)
            if (!dialog.showAndGet()) return false
            val files = dialog.selected().map { it.path }
            if (files.isEmpty()) return false
            val folders = IronHawkCleanup.emptyFolders(root, files)
            val failed = delete(project, files + folders)
            val removed = files.size - failed
            NotificationGroupManager.getInstance().getNotificationGroup("Iron Dome IA").createNotification(
                "Pasta do IronHawk limpa",
                "$removed ${if (removed == 1) "arquivo apagado" else "arquivos apagados"} de ${IronHawkFolder.ROOT}/." +
                    (if (failed > 0) " $failed não puderam ser apagados; veja o log da IDE." else "") +
                    " Se precisar, recupere pelo Local History da IDE.",
                if (failed > 0) NotificationType.WARNING else NotificationType.INFORMATION,
            ).notify(project)
            return removed > 0
        }

        /** Arquivos primeiro, depois as pastas que ficaram vazias. Devolve quantos arquivos falharam. */
        private fun delete(project: Project, paths: List<Path>): Int {
            var failed = 0
            val fileSystem = LocalFileSystem.getInstance()
            val resolved = paths.map { it to fileSystem.refreshAndFindFileByNioFile(it) }
            WriteCommandAction.writeCommandAction(project).withName("Limpar pasta do IronHawk").run<RuntimeException> {
                resolved.forEach { (path, file) ->
                    try {
                        if (file != null) { if (file.isValid) file.delete(this) } else Files.deleteIfExists(path)
                    } catch (e: IOException) {
                        LOG.warn("Não deu para apagar $path", e)
                        if (!Files.isDirectory(path)) failed++
                    }
                }
            }
            return failed
        }
    }
}
