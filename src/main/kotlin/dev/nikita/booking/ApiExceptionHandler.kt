package dev.nikita.booking

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ApiException::class)
    fun domain(exception: ApiException): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.status), exception.message!!)
        body.setProperty("code", exception.code)
        return ResponseEntity.status(exception.status).body(body)
    }

    override fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val body = ProblemDetail.forStatusAndDetail(status, "Request validation failed")
        body.setProperty("code", "INVALID_REQUEST")
        body.setProperty("errors", exception.bindingResult.fieldErrors.map {
            mapOf("field" to it.field, "message" to it.defaultMessage)
        })
        return handleExceptionInternal(exception, body, headers, status, request)
    }
}
