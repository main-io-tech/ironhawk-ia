package com.mio.ironhawk

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mio.irondome.interceptor.AgentRunMode
import com.mio.irondome.interceptor.AgentSettings

/**
 * O Arsenal virou um só, o do Iron Dome. Quem só tinha configurado a IA no Tomahawk leva essa configuração para ele,
 * uma vez; quem já tinha configurado o Iron Dome fica com ela. Onde rodar a IA também: o "onde lançar" do Tomahawk só
 * vale se a Defesa nunca foi escolhida.
 */
object ArsenalMigration {

    private const val DONE_KEY = "IronHawk.Arsenal.Migrated"
    private const val RUN_MODE_DONE_KEY = "IronHawk.Arsenal.RunModeMigrated"
    private val FIELDS = listOf("Provider", "Host", "WslDistro", "Executable", "Model", "TimeoutMinutes", "ExtraArgs")

    fun run() {
        migrateRunMode()
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(DONE_KEY)) return
        props.setValue(DONE_KEY, true)
        val tomahawk = FIELDS.associateWith { props.getValue("Tomahawk.Agent.$it") }.filterValues { !it.isNullOrBlank() }
        if (!AgentSettings.untouched || tomahawk.isEmpty()) return
        tomahawk.forEach { (field, value) -> props.setValue("IronDome.Agent.$field", value) }
        val key = PasswordSafe.instance.getPassword(CredentialAttributes(generateServiceName("Tomahawk IA", "Cursor API key")))
        if (!key.isNullOrBlank() && !AgentSettings.hasCursorApiKey) AgentSettings.cursorApiKey = key
        AgentSettings.clearVerified()
    }

    private fun migrateRunMode() {
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(RUN_MODE_DONE_KEY)) return
        props.setValue(RUN_MODE_DONE_KEY, true)
        val attack = props.getValue("Tomahawk.Agent.RunMode")?.let { name -> AgentRunMode.entries.firstOrNull { it.name == name } } ?: return
        if (props.getValue("IronDome.Agent.RunMode") == null) AgentSettings.runMode = attack
    }
}

/** Ao abrir o projeto, fora da EDT: o cofre de senhas é lento. */
class ArsenalMigrationStartup : ProjectActivity {
    override suspend fun execute(project: Project) = ArsenalMigration.run()
}
