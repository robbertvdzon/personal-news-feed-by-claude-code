package com.vdzon.newsfeedbackend.common

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.ResponseEntity
import org.slf4j.LoggerFactory

@ResponseStatus(HttpStatus.NOT_FOUND)
class NotFoundException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.BAD_REQUEST)
class BadRequestException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.CONFLICT)
class ConflictException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.UNAUTHORIZED)
class UnauthorizedException(message: String) : RuntimeException(message)

@ControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // Verwachte, met @ResponseStatus geannoteerde fouten apart afvangen.
    // Zonder deze handler vangt @ExceptionHandler(Exception::class) ze óók
    // af en degradeert ze naar 500 met `{"error":"<msg>"}` — wat o.a. de
    // audio-endpoint een misleidende 500 gaf wanneer de mp3 nog niet
    // bestond (zou 404 moeten zijn).
    //
    // Loggen op WARN/INFO: zonder log was het 404-antwoord stil en kon
    // de reviewer niet zien wáárom een /audio-call faalde. Geen stack
    // trace — dat is voor onverwachte fouten (zie handleGeneric).
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<Map<String, Any?>> {
        log.warn("404 Not Found: {}", ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to ex.message))
    }

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException): ResponseEntity<Map<String, Any?>> {
        log.warn("400 Bad Request: {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to ex.message))
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<Map<String, Any?>> {
        log.warn("409 Conflict: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to ex.message))
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException): ResponseEntity<Map<String, Any?>> {
        log.warn("401 Unauthorized: {}", ex.message)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to ex.message))
    }

    // Spring's eigen `ResponseStatusException` apart afvangen — anders pakt
    // `handleGeneric(Exception::class)` 'm en degradeert 'm naar HTTP 500
    // met body `{"error":"400 BAD_REQUEST \"<msg>\""}`. De frontend (b.v.
    // de podcast-feed-editor) verwacht 400 met een schone NL-message in
    // `error`, zoals AC #7 van KAN-56 voorschrijft.
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, Any?>> {
        val status = ex.statusCode
        val reason = ex.reason ?: ex.message ?: "error"
        if (status.is5xxServerError) log.error("{} {}", status.value(), reason, ex)
        else log.warn("{} {}", status.value(), reason)
        return ResponseEntity.status(status).body(mapOf("error" to reason))
    }

    // Kapotte/onvolledige request-body is een client-fout: 400, geen 500.
    // Zonder deze handler viel HttpMessageNotReadableException in
    // handleGeneric en kreeg de frontend een misleidende 500.
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: org.springframework.http.converter.HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> {
        log.warn("400 Bad Request (onleesbare body): {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "Ongeldige request-body"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<Map<String, Any?>> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to (ex.message ?: "internal error")))
    }
}
