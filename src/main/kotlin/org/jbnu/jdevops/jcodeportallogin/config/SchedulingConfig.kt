package org.jbnu.jdevops.jcodeportallogin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
class SchedulingConfig {
    @Bean("taskScheduler")
    fun taskScheduler() = ThreadPoolTaskScheduler().apply {
        // Workspace and course reconcilers block while waiting for Generator. Keep
        // schedule transitions, inspector cleanup and IDE notice publishing independent
        // from those waits.
        poolSize = 5
        setThreadNamePrefix("jcode-scheduler-")
        setWaitForTasksToCompleteOnShutdown(false)
    }

    @Bean("courseManagerCacheScheduler")
    fun courseManagerCacheScheduler() = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("course-manager-cache-")
        setWaitForTasksToCompleteOnShutdown(false)
    }
}
