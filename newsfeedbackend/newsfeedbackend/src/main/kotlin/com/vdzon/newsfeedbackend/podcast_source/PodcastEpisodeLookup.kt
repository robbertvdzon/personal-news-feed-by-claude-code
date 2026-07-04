package com.vdzon.newsfeedbackend.podcast_source

/**
 * Leestoegang tot ge-ingeste podcast-afleveringen voor andere modules
 * (zoals de podcast-vertaalflow), zonder dat die aan de repository-
 * internals hoeven. Geïmplementeerd door de JDBC-repository in
 * infrastructure.
 */
interface PodcastEpisodeLookup {
    fun get(username: String, guid: String): PodcastEpisode?
    fun findByRssItemId(username: String, rssItemId: String): PodcastEpisode?
}
