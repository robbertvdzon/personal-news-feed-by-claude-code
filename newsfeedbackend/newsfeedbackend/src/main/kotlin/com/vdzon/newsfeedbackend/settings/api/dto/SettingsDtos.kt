package com.vdzon.newsfeedbackend.settings.api.dto

/** KAN-68: body voor POST /api/settings/event-preferences. */
data class AddEventPreferenceRequest(val name: String)

/** KAN-68: body voor POST /api/settings/event-preferences/remove. */
data class RemoveEventPreferenceRequest(val name: String)
