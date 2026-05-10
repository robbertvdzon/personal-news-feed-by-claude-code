package com.vdzon.newsfeedbackend.settings.domain

import com.vdzon.newsfeedbackend.settings.CategorySettings
import com.vdzon.newsfeedbackend.settings.RssFeedsSettings
import com.vdzon.newsfeedbackend.settings.SettingsService
import com.vdzon.newsfeedbackend.settings.infrastructure.CategorySettingsRepository
import com.vdzon.newsfeedbackend.settings.infrastructure.RssFeedsRepository
import org.springframework.stereotype.Service

@Service
class SettingsServiceImpl(
    private val categoryRepo: CategorySettingsRepository,
    private val rssFeedsRepo: RssFeedsRepository
) : SettingsService {

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
        val list = categoryRepo.load(username)
        if (list.isEmpty()) {
            categoryRepo.save(username, defaultCategories)
            return defaultCategories
        }
        return ensureSystemCategories(list)
    }

    override fun saveCategories(username: String, categories: List<CategorySettings>): List<CategorySettings> {
        val withSystem = ensureSystemCategories(categories)
        categoryRepo.save(username, withSystem)
        return withSystem
    }

    override fun getRssFeeds(username: String): RssFeedsSettings = rssFeedsRepo.load(username)

    override fun saveRssFeeds(username: String, settings: RssFeedsSettings): RssFeedsSettings {
        rssFeedsRepo.save(username, settings)
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
