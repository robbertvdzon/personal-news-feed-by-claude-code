package com.vdzon.newsfeedbackend.admin.api

import com.vdzon.newsfeedbackend.admin.AdminService
import com.vdzon.newsfeedbackend.admin.AdminUserView
import com.vdzon.newsfeedbackend.common.SecurityHelpers
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AdminController(private val service: AdminService) {

    private fun actor() = SecurityHelpers.currentUsername()

    @GetMapping("/users")
    fun list(): List<AdminUserView> = service.listUsers()

    @PutMapping("/users/{username}/password")
    fun resetPassword(
        @PathVariable username: String,
        @RequestBody body: ResetPasswordRequest
    ): Map<String, String> {
        service.resetPassword(username, body.newPassword, actor())
        return mapOf("status" to "ok")
    }

    @PutMapping("/users/{username}/role")
    fun setRole(
        @PathVariable username: String,
        @RequestBody body: SetRoleRequest
    ): Map<String, String> {
        service.setRole(username, body.role, actor())
        return mapOf("status" to "ok")
    }

    @DeleteMapping("/users/{username}")
    fun delete(@PathVariable username: String): Map<String, String> {
        service.deleteUser(username, actor())
        return mapOf("status" to "ok")
    }
}

data class ResetPasswordRequest(val newPassword: String)
data class SetRoleRequest(val role: String)
