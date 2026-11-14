package com.mio.tomahawk.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import com.mio.irondome.base.WrapText
import com.mio.tomahawk.run.SpecKitSetup
import com.mio.tomahawk.run.TomahawkSettings
import com.mio.tomahawk.run.WindowsShell
import com.mio.tomahawk.speckit.SpecKitService
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * O Spec Kit nesta máquina e no projeto: o aviso do `specify` (instalando, faltando, com defeito ou com versão nova),
 * no Windows o shell dos comandos dele e, com [withInit], o comando e os botões de inicializar o projeto.
 */
class SpecKitSetupPanel(private val project: Project, parent: Disposable, withInit: Boolean) :
    JPanel(VerticalLayout(JBUI.scale(8), VerticalLayout.FILL)) {

    private val machine = JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply { isOpaque = false }
    private val shellModel = DefaultComboBoxModel(arrayOf<WindowsShell?>(null) + WindowsShell.entries)
    private val shell = ComboBox(shellModel).apply {
        renderer = SimpleListCellRenderer.create("") { it?.label ?: "Terminal da IDE" }
        addActionListener {
            if (!filling) {
                TomahawkSettings.specKitShell = selectedItem as? WindowsShell
                update()
            }
        }
    }
    private val shellRow = row().apply {
        add(JBLabel("Rodar em").apply { font = JBFont.small().asBold(); foreground = UIUtil.getContextHelpForeground() })
        add(shell)
        isVisible = SystemInfo.isWindows
    }
    private val command = WrapText(font = editorFont())
    private val init = JButton("Inicializar o Spec Kit", AllIcons.Actions.Execute).apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { SpecKitSetup.init(project) }
    }
    private val copy = JButton("Copiar Comando", AllIcons.Actions.Copy).apply {
        toolTipText = "Copia o comando completo, para rodar fora da IDE"
        addActionListener { SpecKitSetup.copyInit(project) }
    }
    private val initBox = JPanel(VerticalLayout(JBUI.scale(6), VerticalLayout.FILL)).apply {
        isOpaque = false
        add(command)
        add(row().apply {
            add(init)
            add(copy)
        })
    }
    private var filling = false

    /** Mostra o comando e os botões de inicializar o projeto. */
    var withInit: Boolean = withInit
        set(value) {
            field = value
            initBox.isVisible = value
        }

    init {
        isOpaque = false
        add(machine)
        add(shellRow)
        add(initBox)
        initBox.isVisible = withInit
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(
            SpecKitSetup.Listener.TOPIC,
            SpecKitSetup.Listener { ApplicationManager.getApplication().invokeLater({ update() }, project.disposed) },
        )
        update()
    }

    fun update() {
        val specify = TomahawkSettings.resolveSpecify()
        val info = SpecKitSetup.infoFor(specify)
        val busy = SpecKitSetup.job != null
        machine.removeAll()
        val job = SpecKitSetup.job
        when {
            job != null -> machine.add(notice(AllIcons.Actions.Execute, Tone.BLUE,
                "${job.label}… Acompanhe na aba \"${job.title}\" do terminal da IDE; esta tela atualiza quando terminar.",
                button("Parar de Esperar") { recheck() }))
            specify == null -> {
                val how = if (TomahawkSettings.resolveUv() != null) "O uv já está aqui e instala o ${SpecKitSetup.PACKAGE}."
                else "Falta também o uv: o instalador oficial da Astral roda antes, e depois o ${SpecKitSetup.PACKAGE}."
                machine.add(notice(AllIcons.General.Warning, Tone.YELLOW,
                    "O specify, o CLI do Spec Kit, não está instalado nesta máquina. $how Tudo roda no terminal da IDE, onde você acompanha.",
                    row().apply {
                        add(primary("Instalar o Spec Kit") { SpecKitSetup.install(project) })
                        add(button("Verificar de Novo") { recheck() })
                    }))
            }
            info?.broken == true -> machine.add(notice(AllIcons.General.Error, Tone.RED,
                "O specify desta máquina está com defeito: o executável existe, mas a instalação dele no uv perdeu o pacote ${SpecKitSetup.PACKAGE}, " +
                    "e nenhum comando do Spec Kit roda. Reinstale por cima (uv tool install --force ${SpecKitSetup.PACKAGE}).",
                row().apply {
                    add(primary("Reinstalar o Spec Kit") { SpecKitSetup.repair(project) })
                    add(button("Verificar de Novo") { recheck() })
                }))
            info != null && info.outdated -> machine.add(notice(AllIcons.General.Information, Tone.BLUE,
                "Há uma versão nova do Spec Kit: v${info.latest.orEmpty()} (aqui está a v${info.version ?: "?"}).",
                button("Atualizar o Spec Kit") { SpecKitSetup.upgrade(project) }))
        }
        machine.isVisible = machine.componentCount > 0
        filling = true
        try {
            shell.selectedItem = TomahawkSettings.specKitShell
        } finally {
            filling = false
        }
        command.text = "specify init --here --integration ${TomahawkSettings.provider.integration}"
        init.isEnabled = specify != null && info?.broken != true && !busy
        init.toolTipText = when {
            specify == null -> "Instale o Spec Kit nesta máquina primeiro"
            info?.broken == true -> "Reinstale o Spec Kit primeiro: o specify desta máquina está com defeito"
            else -> "Roda specify init --here no terminal escolhido"
        }
        copy.isEnabled = specify != null
        revalidate()
        repaint()
    }

    private fun recheck() {
        SpecKitSetup.recheck()
        SpecKitService.getInstance(project).refresh(0)
    }

    private fun notice(icon: javax.swing.Icon, tone: Tone, text: String, action: JComponent): JComponent =
        JPanel(VerticalLayout(JBUI.scale(4), VerticalLayout.FILL)).apply {
            isOpaque = false
            add(NoticeBox(icon, tone).apply { this.text = text })
            add(action)
        }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun primary(text: String, action: () -> Unit) = button(text, action).apply { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true) }

    private fun row() = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply { isOpaque = false }

    private fun editorFont(): Font {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return Font(scheme.editorFontName, Font.PLAIN, JBFont.label().size)
    }
}
