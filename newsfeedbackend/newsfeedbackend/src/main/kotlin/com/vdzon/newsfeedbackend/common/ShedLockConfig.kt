package com.vdzon.newsfeedbackend.common

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "59m")
class ShedLockConfig {
    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcLockProvider(dataSource)
}
