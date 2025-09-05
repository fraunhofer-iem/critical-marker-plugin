package de.fraunhofer.iem

import java.util.Properties

object AppProperties {
    private const val APP_PROP_FILE_NAME = "app.properties"

    private val props = loadAppProperties()

    private fun loadAppProperties(): Properties {
        val tempProps = Properties()

        val stream = this::class.java.classLoader.getResourceAsStream(APP_PROP_FILE_NAME)

        tempProps.load(stream)

        return tempProps
    }

    fun get(key: String): String? = props.getProperty(key)

    val llmPlatform: String get() = props.getProperty("llm.platform")

    val apiKey: String? get() = props.getProperty("api.key")

    val apiUrl: String? get() = props.getProperty("api.url")

    val model: String? get() = props.getProperty("model")

    val temperature: String get() = props.getProperty("model.temperature")
}