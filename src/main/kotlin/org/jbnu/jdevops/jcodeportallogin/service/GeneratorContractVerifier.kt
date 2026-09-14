package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.config.GENERATOR_SCOPE_ATTRIBUTE
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

@Component
class GeneratorContractVerifier(
    @Qualifier("generatorWorkspaceWebClient") private val generator: WebClient,
    @Value("\${workspace.generator-contract-version:3}") private val expectedVersion: String,
    @Value("\${workspace.generator-contract-cache-seconds:30}") private val cacheSeconds: Long
) {
    @Volatile
    private var verifiedUntilNanos: Long = 0

    fun requireCompatible() {
        if (System.nanoTime() < verifiedUntilNanos) return
        synchronized(this) {
            if (System.nanoTime() < verifiedUntilNanos) return
            val response = generator.get()
                .uri("/health/contract")
                .attribute(GENERATOR_SCOPE_ATTRIBUTE, "jcode:read")
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(Duration.ofSeconds(5))
                .block()
                ?: throw IllegalStateException("Generator contract 응답이 없습니다.")
            val actual = response["workspaceContractVersion"]?.toString()
            if (actual != expectedVersion) {
                throw IllegalStateException(
                    "Generator contract version mismatch: expected=$expectedVersion, actual=${actual ?: "missing"}"
                )
            }
            verifiedUntilNanos = System.nanoTime() + Duration.ofSeconds(cacheSeconds.coerceAtLeast(1)).toNanos()
        }
    }
}
