package com.mio.ironhawk

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mio.irondome.interceptor.AgentSettings

/**
 * O Arsenal virou um só, o do Iron Dome. Quem só tinha configurado a IA no Tomahawk leva essa configuração para ele,
 * uma vez; quem já tinha configurado o Iron Dome fica com ela.
 */
object ArsenalMigration {

    private const val DONE_KEY = "IronHawk.Arsenal.Migrated"
    private val FIELDS = listOf("Provider", "Host", "WslDistro", "Executable", "Model", "TimeoutMinutes", "ExtraArgs")

    fun run() {
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
}

/** Ao abrir o projeto, fora da EDT: o cofre de senhas é lento. */
class ArsenalMigrationStartup : ProjectActivity {
    override suspend fun execute(project: Project) = ArsenalMigration.run()
}
