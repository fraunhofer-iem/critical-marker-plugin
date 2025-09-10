package de.fraunhofer.iem.llm

import com.openai.models.chat.completions.ChatCompletion

object Pricing {
    // rates are USD per 1K tokens
    private val rates = mapOf(
        "gpt-3.5-turbo"       to Pair(0.0030, 0.0060),
        "gpt-4-8k"            to Pair(0.0300, 0.0600),
        "gpt-4-32k"           to Pair(0.0600, 0.1200),
        "gpt-4-turbo"         to Pair(0.0100, 0.0300),
        "gpt-4o"              to Pair(0.0025, 0.0100),
        "gpt-4o-mini"         to Pair(0.00015, 0.00060),
        "gpt-4.5"             to Pair(0.0750, 0.1500),
        "o1-preview"          to Pair(0.0150, 0.0600),
        "o1-pro"              to Pair(0.1500, 0.6000),
        "o3"                  to Pair(0.0020, 0.0080),
        "gpt-5"               to Pair(0.00125, 0.0100),
        "gpt-5-mini"          to Pair(0.00025, 0.0020),
        "gpt-5-nano"          to Pair(0.00005, 0.00040)
    )

    var totalCost: Double = 0.0
        private set
    var discountedTotalCost: Double = 0.0
        private set
    var totalInputTokens: Long = 0L
        private set
    var totalOutputTokens: Long = 0L
        private set

    var totalRequestSent: Int = 0
        private set

    fun recordCost(chatCompletion: ChatCompletion, model: String = "gpt-4o", discount: Double = 0.11) {
        val (inputRate, outputRate) = rates[model] ?: error("Unknown model: $model")

        val inTok = chatCompletion.usage().get().promptTokens()
        val outTok = chatCompletion.usage().get().completionTokens()

        val normal = (inTok / 1000.0) * inputRate + (outTok / 1000.0) * outputRate
        val discounted = normal * (1 - discount)

        totalCost += normal
        discountedTotalCost += discounted
        totalInputTokens += inTok
        totalOutputTokens += outTok
        ++totalRequestSent
    }
}