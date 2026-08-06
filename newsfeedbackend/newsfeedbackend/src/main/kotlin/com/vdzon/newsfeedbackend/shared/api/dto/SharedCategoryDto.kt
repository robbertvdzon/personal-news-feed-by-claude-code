package com.vdzon.newsfeedbackend.shared.api.dto

import com.vdzon.newsfeedbackend.settings.CategorySettings

/**
 * Response-DTO voor het publieke `GET /api/shared/categories`.
 *
 * Bewust ZONDER `extraInstructions` en `isSystem` van [CategorySettings]:
 * - `extraInstructions` is vrije tekst waarmee de bron-gebruiker het
 *   taalmodel bijstuurt. Dat is privé, en dit endpoint staat op `permitAll`
 *   (SecurityConfig) en wordt publiek doorgeproxyd — zonder eigen DTO ligt
 *   die tekst dus voor iedereen op internet op straat;
 * - `isSystem` zegt iets over de interne inrichting van de bron-gebruiker
 *   en wordt door niemand gelezen.
 *
 * De enige consument is de reader-app (`frontend-reader/lib/models.dart`,
 * `CategorySettings.fromJson`) en die parseert precies deze drie velden;
 * weglaten van de rest verandert dus niets aan het contract dat echt in
 * gebruik is. Zelfde patroon als [SharedFeedItemDto] voor de feed.
 *
 * Geen `@JsonProperty`-workarounds nodig: die zijn in [SharedFeedItemDto]
 * en [CategorySettings] alleen nodig voor getternamen als `isSummary`/
 * `isSystem`, en die velden zitten hier niet in.
 */
data class SharedCategoryDto(
    val id: String,
    val name: String,
    val enabled: Boolean
)

fun CategorySettings.toSharedDto(): SharedCategoryDto = SharedCategoryDto(
    id = id,
    name = name,
    enabled = enabled
)
