package com.vdzon.newsfeedbackend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@Modulithic
@EnableAsync
@EnableScheduling
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
