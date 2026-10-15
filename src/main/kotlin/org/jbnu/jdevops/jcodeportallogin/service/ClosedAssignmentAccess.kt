package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/** Decides whether closed assignments are shown read-only in course workspaces. */
fun interface ClosedAssignmentAccess {
    fun readOnlyEnabled(now: LocalDateTime): Boolean

    // Non-blocking variant for the portal list and IDE notices; launches and mount
    // plans always use [readOnlyEnabled].
    fun readOnlyEnabledForDisplay(now: LocalDateTime): Boolean =
        runCatching { readOnlyEnabled(now) }.getOrDefault(false)
}

/**
 * Time-based activation. Mount plans and hashes must be identical on every Backend
 * replica, so the switch is an instant configured on all replicas rather than a
 * flag that flips one pod at a time during a rollout. Deploy the setting first,
 * let the rollout finish, and every replica changes behaviour at the same moment.
 *
 * After activation the deployed Generator must also advertise the capability. If it
 * does not (for example after a Generator rollback), closed assignments fall back to
 * the previous behaviour (not mounted) instead of blocking every launch in courses
 * that have one. The capability is cached by [GeneratorContractVerifier], so it only
 * changes when the Generator itself changes.
 */
@Component
class ScheduledClosedAssignmentAccess(
    private val generatorContractVerifier: GeneratorContractVerifier,
    @Value("\${workspace.closed-assignment-read-only.activate-at:}") activateAt: String,
    @Value("\${workspace.closed-assignment-read-only.deactivate-at:}") deactivateAt: String
) : ClosedAssignmentAccess {
    private val activateAt = parse(activateAt)
    private val deactivateAt = parse(deactivateAt)

    override fun readOnlyEnabled(now: LocalDateTime): Boolean =
        isActive(now) && generatorSupportsReadOnlyMounts()

    @Volatile
    private var displayProbeRetryAtNanos: Long = 0

    override fun readOnlyEnabledForDisplay(now: LocalDateTime): Boolean {
        if (!isActive(now)) return false
        generatorContractVerifier.lastKnownSupport(GENERATOR_FEATURE)?.let { return it }
        // Nothing known yet (fresh Backend). Probe once, and while the Generator is
        // unreachable do not make every portal request wait on it again.
        if (System.nanoTime() < displayProbeRetryAtNanos) return false
        return runCatching { generatorSupportsReadOnlyMounts() }.getOrElse {
            displayProbeRetryAtNanos = System.nanoTime() + DISPLAY_PROBE_BACKOFF_NANOS
            false
        }
    }

    internal fun isActive(now: LocalDateTime): Boolean =
        activateAt != null && !now.isBefore(activateAt) &&
            (deactivateAt == null || now.isBefore(deactivateAt))

    private fun generatorSupportsReadOnlyMounts(): Boolean = try {
        generatorContractVerifier.supports(GENERATOR_FEATURE)
    } catch (error: Exception) {
        // Same answer the launch path already gives while the Generator is unreachable.
        throw PublicApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "JCODE_RUNTIME_UNAVAILABLE",
            "JCode 실행 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요."
        )
    }

    companion object {
        // Read-only mounts need this Generator capability.
        const val GENERATOR_FEATURE = "closed-assignment-read-only-mounts"
        private const val DISPLAY_PROBE_BACKOFF_NANOS = 30_000_000_000L

        // Local date-time in the Backend time zone (Asia/Seoul), e.g. 2026-10-20T03:00:00.
        private fun parse(value: String): LocalDateTime? =
            value.trim().takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse)
    }
}
