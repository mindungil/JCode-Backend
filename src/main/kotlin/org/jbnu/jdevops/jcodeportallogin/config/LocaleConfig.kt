package org.jbnu.jdevops.jcodeportallogin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.FixedLocaleResolver
import java.util.Locale

@Configuration
class LocaleConfig {
    @Bean
    fun localeResolver(): LocaleResolver = FixedLocaleResolver(Locale.KOREAN)
}
