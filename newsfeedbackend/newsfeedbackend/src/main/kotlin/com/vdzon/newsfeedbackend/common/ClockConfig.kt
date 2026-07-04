package com.vdzon.newsfeedbackend.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Eén injecteerbare [Clock] voor alle tijd-afhankelijke businesslogica
 * (cleanup-cutoffs, retenties). Productiegedrag is identiek aan
 * `Instant.now()`; tests kunnen de bean vervangen door een `Clock.fixed`
 * om datumlogica deterministisch te testen.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
