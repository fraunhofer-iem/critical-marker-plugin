package de.fraunhofer.iem.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams

object LlmClient {
    private val mapper = jacksonObjectMapper()
    private val explanationCache: MutableMap<String, String> = mutableMapOf()

    fun sendRequest(methodSig: String, metricName: String, metricValue: Number, methodCode: String): String {
        if (explanationCache.containsKey(methodSig)) {
            return explanationCache[methodSig]!!
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

        explanationCache[methodSig] = chatCompletion.choices().first().message()._content().toString()

        return explanationCache.getOrDefault(methodSig, "NA")
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