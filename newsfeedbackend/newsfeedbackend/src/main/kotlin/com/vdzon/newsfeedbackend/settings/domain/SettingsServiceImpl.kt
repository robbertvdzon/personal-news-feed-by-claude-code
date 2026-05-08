package com.vdzon.newsfeedbackend.settings.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import com.vdzon.newsfeedbackend.storage.JsonStore
import org.springframework.stereotype.Service

@Service
class SettingsServiceImpl(private val store: JsonStore) : SettingsService {

    private val defaultCategories = listOf(
        CategorySettings("kotlin", "Kotlin"),
        CategorySettings("flutter", "Flutter"),
        CategorySettings("ai", "AI"),
        CategorySettings("blockchain", "Blockchain"),
        CategorySettings("spring", "Spring"),
        CategorySettings("web_dev", "Web Development"),
        CategorySettings("overig", "Overig", isSystem = true)
    )

    override fun getCategories(username: String): List<CategorySettings> {
        val file = store.userFile(username, "settings.json")
        val list = store.readJsonRef(file, object : TypeReference<List<CategorySettings>>() {}, emptyList())
        if (list.isEmpty()) {
            store.writeJson(file, defaultCategories)
            return defaultCategories
        }
        return ensureSystemCategories(list)
    }

    override fun saveCategories(username: String, categories: List<CategorySettings>): List<CategorySettings> {
        val withSystem = ensureSystemCategories(categories)
        store.writeJson(store.userFile(username, "settings.json"), withSystem)
        return withSystem
    }

    override fun getRssFeeds(username: String): RssFeedsSettings {
        val file = store.userFile(username, "rss_feeds.json")
        return store.readJson(file, RssFeedsSettings::class.java, RssFeedsSettings())
    }

    override fun saveRssFeeds(username: String, settings: RssFeedsSettings): RssFeedsSettings {
        store.writeJson(store.userFile(username, "rss_feeds.json"), settings)
        return settings
    }

    private fun ensureSystemCategories(list: List<CategorySettings>): List<CategorySettings> {
        val result = list.toMutableList()
        defaultCategories.filter { it.isSystem }.forEach { sysCat ->
            if (result.none { it.id == sysCat.id }) result.add(sysCat)
        }
        return result
    }
}
