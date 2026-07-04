package com.vdzon.newsfeedbackend.common

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature

/**
 * Jackson-configuratie. Er is bewust GEEN eigen ObjectMapper-bean meer:
 * de app injecteert overal Spring Boot 4's auto-geconfigureerde Jackson 3
 * [tools.jackson.databind.json.JsonMapper] (injecteerbaar als
 * [tools.jackson.databind.ObjectMapper]). Daarmee gebruiken de HTTP-laag
 * en de app-code (jsonb-opslag, AI-clients, websocket) exact dezelfde
 * mapper-configuratie.
 *
 * De oude Jackson 2-bean vertrouwde op twee instellingen die we hier
 * expliciet vastpinnen (het zijn in Jackson 3 toevallig al de defaults,
 * maar het wire-formaat van bestaande jsonb-rijen mag nooit stilletjes
 * veranderen als die defaults ooit schuiven):
 *
 * - WRITE_DATES_AS_TIMESTAMPS uit: datums (Instant e.d.) als ISO-8601-
 *   string, identiek aan wat Jackson 2 (JavaTimeModule) naar de database
 *   schreef. Java-time-support zit in Jackson 3 ingebouwd; een aparte
 *   JavaTimeModule bestaat niet meer.
 * - FAIL_ON_UNKNOWN_PROPERTIES uit: wees tolerant bij JSON die nog oude
 *   veldnamen bevat (bv. legacy isDailyUpdate na rename naar
 *   isHourlyUpdate). Onbekende velden negeren we i.p.v. te falen.
 *
 * De Kotlin-module (tools.jackson.module:jackson-module-kotlin) wordt door
 * Boot automatisch geregistreerd (spring.jackson.find-and-add-modules=true,
 * de default).
 */
@Configuration
class JacksonConfig {

    @Bean
    fun newsfeedJsonMapperCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder.configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
}
