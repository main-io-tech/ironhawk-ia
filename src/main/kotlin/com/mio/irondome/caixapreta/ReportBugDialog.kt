package com.mio.irondome.caixapreta

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.Image
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Formulário do Reportar Bug: um título e um texto livre, com o print e a gravação só quando o usuário pede. Não é
 * modal, para o print e a gravação poderem acontecer com o formulário escondido enquanto o usuário mostra o problema.
 * Quem organiza o texto e publica é o [BugFlow], depois deste formulário.
 */
internal class ReportBugDialog(
    private val project: Project,
    private val area: Rectangle?,
    private val mechanism: Mechanism,
    private val errors: List<PluginError>,
    private val context: List<Pair<String, String>>,
) : DialogWrapper(project, true) {

    private val titleField = JBTextField().apply { emptyText.text = "Resumo curto do problema" }
    private val text = JBTextArea(9, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Conte do seu jeito: o que deu errado, o que você fez antes e o que esperava que acontecesse"
    }

    private val printButton = JButton("Tirar print", AllIcons.Actions.Dump).apply {
        isEnabled = area != null
        toolTipText = "Esconde este formulário, espera ${PRINT_DELAY_MS / 1000} s para você abrir a tela do problema e captura a janela da IDE"
        addActionListener { takeScreenshot() }
    }
    private val printStatus = JBLabel()
    private val discardPrint = ActionLink("Descartar") { discardScreenshot() }
    private val thumbnailLabel = JBLabel().apply { border = JBUI.Borders.emptyLeft(4); isVisible = false }
    private val recordButton = JButton("Gravar GIF", AllIcons.Debugger.Db_set_breakpoint).apply {
        isEnabled = area != null
        addActionListener { startRecording() }
    }
    private val recordingStatus = JBLabel()
    private val discardRecording = ActionLink("Descartar") { discardRecording() }

    private var screenshot: BufferedImage? = null
    private var recorder: GifRecorder? = null
    private var recording: GifRecorder.Recording? = null
    private var recordingError: String? = null
    private var capturing = false
    private var submitted = false

    init {
        title = "Reportar Bug do Iron Dome IA"
        isModal = false
        setOKButtonText("Continuar")
        init()
        updateCaptures()
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Título:", titleField)
        .addLabeledComponent("O que aconteceu:", JBScrollPane(text).apply { preferredSize = JBUI.size(600, 170) }, true)
        .addComponent(row(printButton, printStatus, discardPrint))
        .addComponent(thumbnailLabel)
        .addComponent(row(recordButton, recordingStatus, discardRecording))
        .addComponent(
            comment(
                "Na próxima tela você vê tudo o que será publicado, e a IA, se estiver pronta, só organiza o seu texto. " +
                    "Este relatório é do plugin, não do seu projeto.",
            ),
        )
        .panel
        .apply { preferredSize = JBUI.size(640, preferredSize.height) }

    override fun doValidate(): ValidationInfo? = when {
        titleField.text.isBlank() -> ValidationInfo("Dê um título ao bug.", titleField)
        text.text.isBlank() -> ValidationInfo("Conte o que aconteceu.", text)
        recorder != null || capturing -> ValidationInfo("Espere o print ou a gravação terminar.")
        else -> null
    }

    override fun doOKAction() {
        val draft = BugDraft(
            title = titleField.text.trim(),
            text = text.text.trim(),
            mechanism = mechanism,
            errors = errors,
            context = context,
            screenshot = screenshot,
            recording = recording?.file,
        )
        submitted = true
        super.doOKAction()
        BugFlow.start(project, draft)
    }

    override fun dispose() {
        recorder?.stop()
        if (!submitted) recording?.file?.let { runCatching { Files.deleteIfExists(it) } }
        super.dispose()
    }

    /** Esconde o formulário, espera o usuário abrir a tela do problema e captura a janela da IDE. */
    private fun takeScreenshot() {
        val area = area ?: return
        capturing = true
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(RECORDING_GROUP).createNotification(
            "Print em ${PRINT_DELAY_MS / 1000} segundos",
            "Deixe à mostra a tela do problema. O formulário volta logo depois.",
            NotificationType.INFORMATION,
        )
        window.isVisible = false
        notification.notify(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(PRINT_DELAY_MS)
            val image = ScreenCapture.screenshot(area)
            ApplicationManager.getApplication().invokeLater({
                notification.expire()
                capturing = false
                if (isDisposed) return@invokeLater
                screenshot = image
                updateCaptures()
                window.isVisible = true
                window.toFront()
            }, ModalityState.any())
        }
    }

    private fun discardScreenshot() {
        screenshot = null
        updateCaptures()
    }

    /** Esconde o formulário e grava a janela da IDE até o usuário parar pela notificação ou o tempo acabar. */
    private fun startRecording() {
        val area = area ?: return
        discardRecording()
        val file = Files.createTempFile("iron-dome-bug-", ".gif")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(RECORDING_GROUP).createNotification(
            "Gravando a janela da IDE para o relatório de bug",
            "Reproduza o problema. A gravação para sozinha em ${GifRecorder.MAX_SECONDS} s.",
            NotificationType.INFORMATION,
        )
        val recorder = GifRecorder(area, file) { result ->
            notification.expire()
            finishRecording(file, result)
        }
        notification.addAction(NotificationAction.createSimpleExpiring("Parar Gravação") { recorder.stop() })
        this.recorder = recorder
        window.isVisible = false
        notification.notify(project)
        recorder.start()
    }

    private fun finishRecording(file: Path, result: Result<GifRecorder.Recording>) {
        recorder = null
        if (isDisposed) {
            Files.deleteIfExists(file)
            return
        }
        result
            .onSuccess { recording = it }
            .onFailure {
                Files.deleteIfExists(file)
                recordingError = "Falha na gravação: ${it.message ?: it.javaClass.simpleName}"
            }
        updateCaptures()
        window.isVisible = true
        window.toFront()
    }

    private fun discardRecording() {
        recording?.file?.let(Files::deleteIfExists)
        recording = null
        recordingError = null
        updateCaptures()
    }

    private fun updateCaptures() {
        val image = screenshot
        printStatus.text = when {
            image != null -> "Print tirado: ${image.width}×${image.height}"
            area == null -> "Print indisponível: a janela da IDE não está visível"
            else -> "Opcional: captura a janela da IDE quando você estiver pronto"
        }
        printStatus.foreground = UIUtil.getContextHelpForeground()
        printButton.text = if (image != null) "Tirar de Novo" else "Tirar print"
        discardPrint.isVisible = image != null
        thumbnailLabel.isVisible = image != null
        thumbnailLabel.icon = image?.let { ImageIcon(thumbnail(it)) }

        val recorded = recording
        recordingStatus.text = when {
            recorded != null -> "GIF gravado: ${recorded.seconds} s, ${StringUtil.formatFileSize(recorded.bytes)}"
            recordingError != null -> recordingError
            area == null -> "Gravação indisponível: a janela da IDE não está visível"
            else -> "Opcional: grava a janela da IDE por até ${GifRecorder.MAX_SECONDS} s enquanto você reproduz o bug"
        }
        recordingStatus.foreground = if (recordingError != null && recorded == null) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        recordButton.text = if (recorded != null) "Gravar de Novo" else "Gravar GIF"
        discardRecording.isVisible = recorded != null
    }

    private fun row(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        components.forEach(::add)
    }

    private fun comment(text: String) = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun thumbnail(image: BufferedImage): Image {
        val width = JBUI.scale(THUMBNAIL_WIDTH)
        return image.getScaledInstance(width, (image.height * width.toDouble() / image.width).toInt().coerceAtLeast(1), Image.SCALE_SMOOTH)
    }

    companion object {
        const val RECORDING_GROUP = "Iron Dome IA: Gravação"
        const val PRINT_DELAY_MS = 3_000L
        private const val THUMBNAIL_WIDTH = 320
    }
}
