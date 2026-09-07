package org.jbnu.jdevops.jcodeportallogin.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession

@Configuration
@EnableRedisHttpSession
@EnableRedisRepositories(basePackages = ["org.jbnu.jdevops.jcodeportallogin.repo.redis"]) // Redis 전용 리포지토리만 사용
class RedisConfig(
    @Value("\${redis.host}") private val redisHost: String,
    @Value("\${redis.port}") private val redisPort: Int,
    @Value("\${redis.password}") private val redisPassword: String
) {

    @Bean
    fun redisConnectionFactory(): RedisConnectionFactory {
        val redisConfig = RedisStandaloneConfiguration(redisHost, redisPort)
        if (redisPassword.isNotBlank()) {
            redisConfig.setPassword(RedisPassword.of(redisPassword))
        }
        return LettuceConnectionFactory(redisConfig)
    }

    @Bean
    fun redisTemplate(): RedisTemplate<String, Any> {
        return RedisTemplate<String, Any>().apply {
            connectionFactory = redisConnectionFactory()
            keySerializer = StringRedisSerializer()
            valueSerializer = GenericJackson2JsonRedisSerializer()
        }
    }

}
