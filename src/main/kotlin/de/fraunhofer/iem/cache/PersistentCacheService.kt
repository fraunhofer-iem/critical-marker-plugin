package de.fraunhofer.iem.cache

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for persistent caching of LLM responses and signature explanations.
 * Cache data is stored in the project's .idea directory to survive IDE restarts.
 */
@Service(Service.Level.PROJECT)
class PersistentCacheService(private val project: Project) {
    private val log = Logger.getInstance(PersistentCacheService::class.java)
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    
    // In-memory caches for fast access
    private val llmCache: MutableMap<String, CachedLlmResponse> = ConcurrentHashMap()
    private val explanationCache: MutableMap<String, String> = ConcurrentHashMap()
    private val levelsCache: MutableMap<String, String> = ConcurrentHashMap()
    
    // Cache metadata
    private var lastProjectHash: String? = null
    private var cacheTimestamp: Instant? = null
    
    companion object {
        private const val CACHE_DIR_NAME = "security-marker-cache"
        private const val LLM_CACHE_FILE = "llm_cache.json"
        private const val EXPLANATION_CACHE_FILE = "explanation_cache.json"
        private const val LEVELS_CACHE_FILE = "levels_cache.json"
        private const val METADATA_FILE = "cache_metadata.json"
    }
    
    init {
        log.info("Initializing PersistentCacheService for project: ${project.name}")
        log.info("Project base path: ${project.basePath}")
        log.info("Project file path: ${project.projectFilePath}")
        loadCacheFromDisk()
    }
    
    /**
     * Get LLM response from cache, returns null if not found
     */
    fun getLlmResponse(cacheKey: String): String? {
        val response = llmCache[cacheKey]?.response
        log.info("Getting LLM response for key '$cacheKey': ${if (response != null) "FOUND" else "NOT FOUND"}")
        return response
    }
    
    /**
     * Store LLM response in cache (both memory and disk)
     */
    fun storeLlmResponse(cacheKey: String, response: String) {
        log.info("Storing LLM response for key '$cacheKey'")
        llmCache[cacheKey] = CachedLlmResponse(response, Instant.now())
        saveLlmCacheToDisk()
    }
    
    /**
     * Get explanation from cache, returns null if not found
     */
    fun getExplanation(signature: String): String? {
        return explanationCache[signature]
    }
    
    /**
     * Store explanation in cache (both memory and disk)
     */
    fun storeExplanation(signature: String, explanation: String) {
        explanationCache[signature] = explanation
        saveExplanationCacheToDisk()
    }
    
    /**
     * Get level from cache, returns null if not found
     */
    fun getLevel(signature: String): String? {
        return levelsCache[signature]
    }
    
    /**
     * Store level in cache (both memory and disk)
     */
    fun storeLevel(signature: String, level: String) {
        levelsCache[signature] = level
        saveLevelsCacheToDisk()
    }
    
    /**
     * Store multiple explanations at once (more efficient for bulk operations)
     */
    fun storeExplanations(explanations: Map<String, String>) {
        explanationCache.putAll(explanations)
        saveExplanationCacheToDisk()
    }
    
    /**
     * Store multiple levels at once (more efficient for bulk operations)
     */
    fun storeLevels(levels: Map<String, String>) {
        levelsCache.putAll(levels)
        saveLevelsCacheToDisk()
    }
    
    /**
     * Get all cached signatures
     */
    fun getAllSignatures(): Set<String> {
        return explanationCache.keys
    }
    
    /**
     * Check if cache is valid for current project state
     * For persistent caching, we'll be very permissive and only invalidate if explicitly cleared
     */
    fun isCacheValid(): Boolean {
        // For now, always consider cache valid if it has a timestamp
        // This ensures cache persists across IDE restarts
        return cacheTimestamp != null
    }
    
    /**
     * Clear all caches
     */
    fun clearAllCaches() {
        llmCache.clear()
        explanationCache.clear()
        levelsCache.clear()
        lastProjectHash = null
        cacheTimestamp = null
        
        // Clear disk cache
        deleteCacheFiles()
    }
    
    /**
     * Update cache metadata when project changes
     */
    fun updateCacheMetadata() {
        lastProjectHash = calculateProjectHash()
        cacheTimestamp = Instant.now()
        saveMetadataToDisk()
    }
    
    private fun getCacheDirectory(): Path {
        val projectPath = Paths.get(project.basePath ?: project.projectFilePath ?: "")
        val ideaDir = projectPath.resolve(".idea")
        val cacheDir = ideaDir.resolve(CACHE_DIR_NAME)
        log.info("Cache directory path: $cacheDir")
        return cacheDir
    }
    
    private fun ensureCacheDirectoryExists(): Path {
        val cacheDir = getCacheDirectory()
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir)
        }
        return cacheDir
    }
    
    private fun loadCacheFromDisk() {
        try {
            val cacheDir = getCacheDirectory()
            log.info("Attempting to load cache from directory: $cacheDir")
            
            if (!Files.exists(cacheDir)) {
                log.info("Cache directory does not exist, starting with empty cache")
                return
            }
            
            // Load metadata first
            loadMetadataFromDisk()
            log.info("Loaded metadata: projectHash=$lastProjectHash, timestamp=$cacheTimestamp")
            
            // Check if cache is valid
            if (!isCacheValid()) {
                log.info("Cache is invalid for current project state, clearing cache")
                clearAllCaches()
                return
            }
            
            // Load LLM cache
            loadLlmCacheFromDisk()
            
            // Load explanation cache
            loadExplanationCacheFromDisk()
            
            // Load levels cache
            loadLevelsCacheFromDisk()
            
            log.info("Successfully loaded cache from disk: ${llmCache.size} LLM responses, ${explanationCache.size} explanations, ${levelsCache.size} levels")
            
        } catch (e: Exception) {
            log.warn("Failed to load cache from disk: ${e.message}")
            clearAllCaches()
        }
    }
    
    private fun loadMetadataFromDisk() {
        try {
            val metadataFile = getCacheDirectory().resolve(METADATA_FILE)
            if (Files.exists(metadataFile)) {
                val metadata: CacheMetadata = mapper.readValue(metadataFile.toFile())
                lastProjectHash = metadata.projectHash
                cacheTimestamp = metadata.timestamp?.let { Instant.parse(it) }
            }
        } catch (e: Exception) {
            log.warn("Failed to load cache metadata: ${e.message}")
        }
    }
    
    private fun loadLlmCacheFromDisk() {
        try {
            val cacheFile = getCacheDirectory().resolve(LLM_CACHE_FILE)
            if (Files.exists(cacheFile)) {
                val cacheData: Map<String, CachedLlmResponse> = mapper.readValue(cacheFile.toFile())
                llmCache.putAll(cacheData)
            }
        } catch (e: Exception) {
            log.warn("Failed to load LLM cache from disk: ${e.message}")
        }
    }
    
    private fun loadExplanationCacheFromDisk() {
        try {
            val cacheFile = getCacheDirectory().resolve(EXPLANATION_CACHE_FILE)
            if (Files.exists(cacheFile)) {
                val cacheData: Map<String, String> = mapper.readValue(cacheFile.toFile())
                explanationCache.putAll(cacheData)
            }
        } catch (e: Exception) {
            log.warn("Failed to load explanation cache from disk: ${e.message}")
        }
    }
    
    private fun loadLevelsCacheFromDisk() {
        try {
            val cacheFile = getCacheDirectory().resolve(LEVELS_CACHE_FILE)
            if (Files.exists(cacheFile)) {
                val cacheData: Map<String, String> = mapper.readValue(cacheFile.toFile())
                levelsCache.putAll(cacheData)
            }
        } catch (e: Exception) {
            log.warn("Failed to load levels cache from disk: ${e.message}")
        }
    }
    
    private fun saveLlmCacheToDisk() {
        try {
            val cacheDir = ensureCacheDirectoryExists()
            val cacheFile = cacheDir.resolve(LLM_CACHE_FILE)
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), llmCache)
            log.info("Saved LLM cache to disk: ${llmCache.size} entries")
        } catch (e: Exception) {
            log.warn("Failed to save LLM cache to disk: ${e.message}")
        }
    }
    
    private fun saveExplanationCacheToDisk() {
        try {
            val cacheDir = ensureCacheDirectoryExists()
            val cacheFile = cacheDir.resolve(EXPLANATION_CACHE_FILE)
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), explanationCache)
        } catch (e: Exception) {
            log.warn("Failed to save explanation cache to disk: ${e.message}")
        }
    }
    
    private fun saveLevelsCacheToDisk() {
        try {
            val cacheDir = ensureCacheDirectoryExists()
            val cacheFile = cacheDir.resolve(LEVELS_CACHE_FILE)
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), levelsCache)
        } catch (e: Exception) {
            log.warn("Failed to save levels cache to disk: ${e.message}")
        }
    }
    
    private fun saveMetadataToDisk() {
        try {
            val cacheDir = ensureCacheDirectoryExists()
            val metadataFile = cacheDir.resolve(METADATA_FILE)
            val metadata = CacheMetadata(
                projectHash = lastProjectHash,
                timestamp = cacheTimestamp?.toString()
            )
            mapper.writerWithDefaultPrettyPrinter().writeValue(metadataFile.toFile(), metadata)
        } catch (e: Exception) {
            log.warn("Failed to save cache metadata to disk: ${e.message}")
        }
    }
    
    private fun deleteCacheFiles() {
        try {
            val cacheDir = getCacheDirectory()
            if (Files.exists(cacheDir)) {
                Files.walk(cacheDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        } catch (e: Exception) {
            log.warn("Failed to delete cache files: ${e.message}")
        }
    }
    
    private fun calculateProjectHash(): String {
        // Create a hash based on project structure (but not file modification times)
        // This helps determine if the project structure has changed significantly
        val projectPath = project.basePath ?: project.projectFilePath ?: ""
        val sourceRoots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentSourceRoots
        
        val hashInput = buildString {
            append(projectPath)
            sourceRoots.forEach { root ->
                append(root.path)
                // Only include file names and structure, not modification times
                try {
                    Files.walk(Paths.get(root.path))
                        .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                        .forEach { file ->
                            // Only include file path, not modification time
                            append(file.toString())
                        }
                } catch (e: Exception) {
                    // Ignore file system errors
                }
            }
        }
        
        return hashInput.hashCode().toString()
    }
}

/**
 * Data class for cached LLM responses with timestamp
 */
data class CachedLlmResponse(
    val response: String,
    val timestamp: Instant
)

/**
 * Data class for cache metadata
 */
data class CacheMetadata(
    val projectHash: String?,
    val timestamp: String?
)
