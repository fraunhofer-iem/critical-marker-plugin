package de.fraunhofer.iem.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.ProjectManager
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import de.fraunhofer.iem.cache.PersistentCacheService

object LlmClient {
    private val mapper = jacksonObjectMapper()

    fun sendRequest(methodSig: String, metricName: String, metricValue: Number, methodCode: String): String {
        val cacheKey = "$methodSig|$metricName|$metricValue"
        
        // Try to get from persistent cache first
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        println("DEBUG: Found ${ProjectManager.getInstance().openProjects.size} open projects")
        if (project != null) {
            println("DEBUG: Using project: ${project.name}")
            val cacheService = project.getService(PersistentCacheService::class.java)
            val cachedResponse = cacheService.getLlmResponse(cacheKey)
            if (cachedResponse != null) {
                println("DEBUG: Found cached LLM response for key: $cacheKey")
                return cachedResponse
            } else {
                println("DEBUG: No cached LLM response found for key: $cacheKey")
            }
        } else {
            println("DEBUG: No open project found")
        }

        val llmConfig = LlmConfig()

        val client = OpenAIOkHttpClient.builder()
            .baseUrl(llmConfig.apiURL)
            .apiKey(llmConfig.apiKey)
            .queryParams(mapOf("api-version" to listOf("2024-02-01")))
            .build()

        val params = ChatCompletionCreateParams.builder()
            .addSystemMessage(PromptTemplate.getSystemPrompt())
            .addUserMessage(PromptTemplate.buildUserPrompt(metricName, metricValue, methodCode))
            .model(llmConfig.model)
            .temperature(llmConfig.temperature.toDouble())
            .build()

        val chatCompletion: ChatCompletion = client.chat().completions().create(params)

        if (chatCompletion.usage().isPresent) {
            Pricing.recordCost(chatCompletion)
        }

        val response = chatCompletion.choices().first().message()._content().toString()
        
        // Store in persistent cache
        if (project != null) {
            val cacheService = project.getService(PersistentCacheService::class.java)
            cacheService.storeLlmResponse(cacheKey, response)
            println("DEBUG: Stored LLM response in cache for key: $cacheKey")
        } else {
            println("DEBUG: No project available to store cache")
        }

        return response
    }


    /**
     * Build the complete prompt by combining the system and user prompts
     */
    private fun buildRequestBody(systemPrompt: String, userPrompt: String, temperature: String): String {
        val usrPromptJson = mapper.writeValueAsString(userPrompt)
        val sysPromptJson = mapper.writeValueAsString(systemPrompt)
        return """
      {
        "messages": [
          {"role": "system", "content": $sysPromptJson},
          {"role": "user", "content": $usrPromptJson}
        ],
        "temperature": $temperature
      }
  """.trimIndent()
    }

    /**
     * Parses the given responses by the LLM. The explanation is in the field choices -> message -> content
     */
    private fun parseResponse(response: String): String? {
        val result: Map<String, Any> = mapper.readValue(response)
        val choices = result["choices"] as? List<Map<String, Any>>
        val message = choices?.firstOrNull()?.get("message") as? Map<String, Any>
        return message?.get("content") as? String
    }
}