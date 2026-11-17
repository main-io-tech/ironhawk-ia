package com.mio.irondome.interceptor

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import com.mio.ironhawk.ArsenalPanel
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Settings > Tools > IronHawk IA > Arsenal (IA): qual CLI de IA os dois sistemas usam, e como. */
class AgentConfigurable : Configurable {

    private val provider = ComboBox(AgentProvider.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.title} CLI (${it.executables.first()})" }
        addActionListener { updateHints() }
    }
    private val host = ComboBox(AgentHost.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        addActionListener { updateHints() }
    }
    private val distro = ComboBox<String>().apply {
        isEditable = true
        addActionListener { updateHints() }
    }
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Executável do CLI de IA"))
    }
    private val model = JBTextField().apply { emptyText.text = "Padrão do CLI" }
    private val timeout = JSpinner(SpinnerNumberModel(AgentSettings.DEFAULT_TIMEOUT_MINUTES, 1, AgentSettings.MAX_TIMEOUT_MINUTES, 5))
    private val extraArgs = JBTextField().apply { emptyText.text = "Ex.: --approve-mcps" }
    /** Vazio mantém a chave salva; só o Remover a apaga, como na aba Arsenal. */
    private val cursorKey = JBPasswordField()
    private val keyState = JBLabel()
    private val removeKey = ActionLink("Remover a chave salva") {
        ApplicationManager.getApplication().executeOnPooledThread { AgentSettings.cursorApiKey = null }
        cursorKey.text = ""
        updateKeyState(saved = false)
    }
    /** O CLI achado nesta máquina, por `provedor|caminho`: procurar lê o PATH e o disco, fora da EDT. */
    private val localFound = mutableMapOf<String, String?>()
    private val runMode = ComboBox(AgentRunMode.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val found = JBLabel()
    private val status = JBLabel()
    private val warning = JBLabel().apply { foreground = JBColor(0x9A6700, 0xD29922) }
    private val autoStatus = JBLabel()
    private val terminalStatus = JBLabel()
    private val autoFailure = NoticeBox(AllIcons.General.Warning, Tone.YELLOW).apply { isVisible = false }

    override fun getDisplayName() = "Arsenal (IA)"

    override fun createComponent(): JComponent {
        val test = JButton("Salvar e Testar", AllIcons.Actions.Execute).apply { addActionListener { test() } }
        val auto = JButton("Configurar Automaticamente", IronDomeIcons.ia)
        auto.addActionListener { autoSetup(auto, test) }
        if (AgentLaunch.wslSupported) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val names = AgentLaunch.distributions()
                ApplicationManager.getApplication().invokeLater({
                    val chosen = selectedDistro() ?: AgentSettings.wslDistro
                    distro.removeAllItems()
                    names.forEach(distro::addItem)
                    distro.selectedItem = chosen ?: names.firstOrNull()
                }, ModalityState.any())
            }
        }
        val builder = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Configuração automática"))
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(auto)
                add(autoStatus)
            })
            .addComponent(comment(ArsenalPanel.AUTO_SETUP_HINT))
            .addComponent(autoFailure)
            .addComponent(TitledSeparator("CLI de IA"))
            .addLabeledComponent("CLI:", provider)
            .addComponentToRightColumn(warning)
        if (AgentLaunch.wslSupported) {
            builder.addLabeledComponent("Onde está o CLI:", host)
                .addComponentToRightColumn(comment(ArsenalPanel.HOST_HINT))
                .addLabeledComponent("Distribuição do WSL:", distro)
        }
        return builder
            .addLabeledComponent("Executável:", executable)
            .addComponentToRightColumn(found)
            .addLabeledComponent("Modelo:", model)
            .addComponentToRightColumn(comment("Opcional, como no --model do CLI. Vazio usa o modelo padrão da sua conta."))
            .addLabeledComponent("Tempo máximo (min):", timeout)
            .addComponentToRightColumn(comment("Uma tarefa que passar disso é interrompida."))
            .addLabeledComponent("Argumentos extras:", extraArgs)
            .addComponentToRightColumn(comment("Vão para o CLI antes do pedido, separados por espaço; aspas agrupam."))
            .addLabeledComponent("Onde rodar a IA:", runMode)
            .addComponentToRightColumn(comment(ArsenalPanel.RUN_MODE_HINT))
            .addComponent(TitledSeparator("Cursor"))
            .addLabeledComponent("Chave de API do Cursor:", cursorKey)
            .addComponentToRightColumn(comment(ArsenalPanel.CURSOR_KEY_HINT))
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(keyState)
                add(removeKey)
            })
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(test)
                add(status)
            })
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(JButton("Salvar e Testar no Terminal", AllIcons.Debugger.Console).apply {
                    isEnabled = AgentTerminal.available() != null
                    toolTipText = if (isEnabled) "Abre o terminal do projeto aberto e roda o CLI com esta configuração, no Windows ou no WSL"
                    else "O plugin de terminal da IDE está desligado"
                    addActionListener { testTerminal(this) }
                })
                add(terminalStatus)
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean =
        provider.selectedItem != AgentSettings.provider ||
            host.selectedItem != AgentSettings.host ||
            (host.selectedItem == AgentHost.WSL && selectedDistro() != AgentSettings.wslDistro) ||
            executable.text.trim() != AgentSettings.executable.orEmpty() ||
            model.text.trim() != AgentSettings.model.orEmpty() ||
            (timeout.value as Int) != AgentSettings.timeoutMinutes ||
            extraArgs.text.trim() != AgentSettings.extraArgs.orEmpty() ||
            runMode.selectedItem != AgentSettings.runMode ||
            String(cursorKey.password).isNotBlank()

    override fun apply() = saveFields()

    /** Grava os campos; a chave, se o campo tiver uma, vai para o cofre fora da EDT, e só depois roda [then], na EDT. */
    private fun saveFields(then: (() -> Unit)? = null) {
        AgentSettings.provider = provider.selectedItem as AgentProvider
        AgentSettings.host = host.selectedItem as? AgentHost ?: AgentHost.LOCAL
        if (host.selectedItem == AgentHost.WSL) AgentSettings.wslDistro = selectedDistro()
        AgentSettings.executable = executable.text
        AgentSettings.model = model.text
        AgentSettings.timeoutMinutes = timeout.value as Int
        AgentSettings.extraArgs = extraArgs.text
        AgentSettings.runMode = runMode.selectedItem as AgentRunMode
        val key = String(cursorKey.password).trim()
        cursorKey.text = ""
        if (key.isNotEmpty()) updateKeyState(saved = true)
        updateHints()
        if (key.isEmpty() && then == null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (key.isNotEmpty()) AgentSettings.cursorApiKey = key
            then?.let { ApplicationManager.getApplication().invokeLater(it, ModalityState.any()) }
        }
    }

    override fun reset() {
        provider.selectedItem = AgentSettings.provider
        host.selectedItem = AgentSettings.host
        distro.selectedItem = AgentSettings.wslDistro
        executable.text = AgentSettings.executable.orEmpty()
        model.text = AgentSettings.model.orEmpty()
        timeout.value = AgentSettings.timeoutMinutes
        extraArgs.text = AgentSettings.extraArgs.orEmpty()
        runMode.selectedItem = AgentSettings.runMode
        cursorKey.text = ""
        updateKeyState(AgentSettings.hasCursorApiKey)
        updateHints()
        // O teste vale para a configuração de agora, e conferi-la procura o CLI no PATH: fora da EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val tested = AgentSettings.verifiedAt?.let { "Testada em $it${AgentSettings.launch()?.where.orEmpty()}." }
            val terminal = AgentSettings.terminalVerifiedAt
            ApplicationManager.getApplication().invokeLater({
                if (tested != null) show(tested, ok = true) else show("Ainda não testada: os botões de IA ficam desligados até o teste passar.", ok = false)
                terminal?.let { showTerminal("Terminal testado em $it.", ok = true) }
            }, ModalityState.any())
        }
    }

    private fun updateKeyState(saved: Boolean) {
        keyState.text = if (saved) "Há uma chave salva; deixe o campo vazio para mantê-la." else "Nenhuma chave salva."
        keyState.foreground = UIUtil.getContextHelpForeground()
        removeKey.isVisible = saved
    }

    private fun selectedDistro(): String? = ((distro.selectedItem as? String) ?: distro.editor.item?.toString())?.trim()?.ifEmpty { null }

    private fun updateHints() {
        val chosen = provider.selectedItem as? AgentProvider ?: return
        val inWsl = host.selectedItem == AgentHost.WSL && AgentLaunch.wslSupported
        distro.isEnabled = inWsl
        if (inWsl) {
            found.text = selectedDistro()?.let { "No WSL $it, o CLI é procurado pelo shell de login (~/.local/bin) ao testar; vazio usa ${chosen.executables.first()}." }
                ?: "Escolha a distribuição do WSL."
            found.foreground = if (selectedDistro() != null) UIUtil.getContextHelpForeground() else JBColor.RED
        } else {
            val configured = executable.text.trim().ifEmpty { null }
            val key = "${chosen.name}|${configured.orEmpty()}"
            if (key in localFound) {
                val path = localFound[key]
                found.text = path?.let { "Encontrado: $it" } ?: "Não encontrado. ${chosen.installHint}"
                found.foreground = if (path != null) UIUtil.getContextHelpForeground() else JBColor.RED
            } else {
                found.text = "Procurando o ${chosen.title} CLI…"
                found.foreground = UIUtil.getContextHelpForeground()
                ApplicationManager.getApplication().executeOnPooledThread {
                    val path = AgentSettings.resolveExecutable(chosen, configured)?.toString()
                    ApplicationManager.getApplication().invokeLater({
                        localFound[key] = path
                        updateHints()
                    }, ModalityState.any())
                }
            }
        }
        warning.text = if (chosen == AgentProvider.ANTIGRAVITY) "<html>${ArsenalPanel.antigravityHint()}</html>" else ""
    }

    private fun autoSetup(auto: JButton, test: JButton) {
        auto.isEnabled = false
        test.isEnabled = false
        autoFailure.isVisible = false
        AgentAutoSetup.run(
            onProgress = { message ->
                autoStatus.text = message
                autoStatus.foreground = UIUtil.getContextHelpForeground()
            },
            onDone = { result ->
                auto.isEnabled = true
                test.isEnabled = true
                reset()
                autoStatus.text = if (result.ok) result.message else "Não deu para configurar sozinho: termine abaixo."
                autoStatus.foreground = if (result.ok) JBColor(0x1A7F37, 0x3FB950) else JBColor.RED
                autoFailure.text = result.message
                autoFailure.isVisible = !result.ok
            },
        )
    }

    /** O terminal é de um projeto: vale o primeiro aberto. */
    private fun testTerminal(button: JButton) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        if (project == null) {
            showTerminal("Abra um projeto: o teste usa o terminal dele.", ok = false)
            return
        }
        button.isEnabled = false
        showTerminal("Abrindo o terminal de ${project.name} e esperando a IA gravar OK…", ok = null)
        saveFields {
            AgentVerifier.testTerminal(project) { outcome ->
                button.isEnabled = true
                showTerminal(outcome.message, outcome.ok)
            }
        }
    }

    private fun showTerminal(text: String, ok: Boolean?) {
        terminalStatus.text = "<html>$text</html>"
        terminalStatus.foreground = when (ok) {
            true -> JBColor(0x1A7F37, 0x3FB950)
            false -> JBColor.RED
            null -> UIUtil.getContextHelpForeground()
        }
    }

    /** Salva e testa: só com o teste passando os botões de IA se habilitam. */
    private fun test() {
        show("Testando ${(provider.selectedItem as AgentProvider).title}…", ok = null)
        saveFields { AgentVerifier.test { outcome -> show(outcome.message, ok = outcome.ok) } }
    }

    private fun show(text: String, ok: Boolean?) {
        status.text = text
        status.foreground = when (ok) {
            true -> JBColor(0x1A7F37, 0x3FB950)
            false -> JBColor.RED
            null -> UIUtil.getContextHelpForeground()
        }
    }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
}
