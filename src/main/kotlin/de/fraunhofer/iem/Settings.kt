package de.fraunhofer.iem

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger


@Service(Service.Level.APP)
class Settings {
    private val log = Logger.getInstance(Settings::class.java)

    companion object {
        fun getInstance(): Settings = ApplicationManager.getApplication().getService(Settings::class.java)


        private fun parseJsonMap(text: String): Map<String, String> {
// tiny JSON parser to avoid bringing a dependency; assumes a flat object of string:string
            val regex = "\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"".toRegex()
            return regex.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        }
    }
}