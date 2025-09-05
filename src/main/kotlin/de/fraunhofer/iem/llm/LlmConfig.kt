package de.fraunhofer.iem.llm

import de.fraunhofer.iem.AppProperties

class LlmConfig () {
    val platform = AppProperties.llmPlatform
    val apiURL = AppProperties.apiUrl!!
    val apiKey = AppProperties.apiKey!!
    val model = AppProperties.model!!
    val temperature = AppProperties.temperature
}