package com.vdzon.newsfeedbackend.admin

/**
 * Public interface van de admin-module. Alleen aanroepbaar door users met
 * role=admin (zie SecurityConfig).
 */
interface AdminService {
    fun listUsers(): List<AdminUserView>
    fun resetPassword(targetUsername: String, newPassword: String, actor: String)
    fun setRole(targetUsername: String, newRole: String, actor: String)
    fun deleteUser(targetUsername: String, actor: String)
}

/**
 * View van een user voor in de admin-lijst. Bewust géén passwordHash.
 */
data class AdminUserView(
    val id: String,
    val username: String,
    val role: String
)
