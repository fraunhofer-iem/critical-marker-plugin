package de.fraunhofer.iem.metricsUtil

import weka.core.Attribute
import weka.core.DenseInstance
import weka.core.Instances
import weka.filters.Filter
import weka.filters.unsupervised.attribute.Discretize
import java.util.LinkedHashMap

object QuantileClassificationWithEqualFreq {

    /**
     * Discretize scores into qualitative labels.
     *
     * @param nameToScore   method name -> score (insertion order preserved if LinkedHashMap)
     * @param bins          desired number of bins (e.g., 3 for Low/Med/High)
     * @param equalFrequency false = equal-width, true = equal-frequency (quantile-like)
     * @param labels        labels for bins (index 0 = lowest bin). If null or too short, auto-generates.
     * @return LinkedHashMap preserving input order: method name -> label
     */
    @Throws(Exception::class)
    fun discretize(
        nameToScore: Map<String, Double>,
        bins: Int,
        equalFrequency: Boolean,
        labels: List<String>?
    ): LinkedHashMap<String, String> {

        // Preserve input order
        val entries = nameToScore.entries.toList()

        // Build a 1-column WEKA dataset of the scores
        val attrs = arrayListOf(Attribute("score"))
        val data = Instances("scores", attrs, entries.size)
        for (e in entries) {
            val inst = DenseInstance(1)
            inst.setValue(0, e.value)
            data.add(inst)
        }

        // Configure Discretize
        val disc = Discretize().apply {
            setBins(bins)
            setUseEqualFrequency(equalFrequency) // true => quantile/equal-frequency
            setUseBinNumbers(true)               // output "0","1",... so we can map to labels
            setAttributeIndices("first-last")
        }
        disc.setInputFormat(data)

        // Apply filter
        val binned: Instances = Filter.useFilter(data, disc)

        // Determine actual number of bins (can be < requested)
        val actualBins = binned.attribute(0).numValues()

        // Special case: only one bin -> always map to LAST label
        if (actualBins == 1) {
            val chosenLabel = labels?.lastOrNull() ?: "HIGH"
            val out = LinkedHashMap<String, String>(nameToScore.size)
            for ((name, _) in nameToScore) {
                out[name] = chosenLabel
            }
            return out
        }

        // Prepare labels
        val finalLabels: List<String> = if (labels == null || labels.size < actualBins) {
            List(actualBins) { i -> "Category ${i + 1}" }
        } else {
            labels
        }

        // Build output map (preserve original order)
        val out = LinkedHashMap<String, String>(entries.size)
        for (i in 0 until binned.numInstances()) {
            val method = entries[i].key
            val binIdx = binned.instance(i).value(0).toInt() // 0-based index
            val label = if (binIdx in finalLabels.indices) {
                finalLabels[binIdx]
            } else {
                "Bin $binIdx"
            }
            out[method] = label
        }

        return out
    }
}
