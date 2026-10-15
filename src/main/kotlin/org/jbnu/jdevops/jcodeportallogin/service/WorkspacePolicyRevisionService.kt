package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.WorkspaceOperationRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Service
class WorkspacePolicyRevisionService(
    private val courseRepository: CourseRepository,
    private val jCodeRepository: JCodeRepository,
    private val operationRepository: WorkspaceOperationRepository,
    private val redisService: RedisService,
    private val workspaceAccessPolicy: WorkspaceAccessPolicy? = null,
    @Value("\${workspace.restart-notice-seconds:5}")
    private val restartNoticeSeconds: Long = 5
) {
    @Transactional
    fun bump(courseId: Long, reason: PolicyChangeReason = PolicyChangeReason.POLICY_CHANGED): Long {
        val course = courseRepository.findByIdForUpdate(courseId).orElseThrow {
            IllegalStateException("Workspace 정책 대상 강의를 찾을 수 없습니다: $courseId")
        }
        val revision = course.workspacePolicyRevision + 1
        course.workspacePolicyRevision = revision
        courseRepository.save(course)

        applyRevision(course, revision, enqueueReconcile = true, reason = reason)
        return revision
    }

    @Transactional
    fun initializeExistingCourse(courseId: Long): Boolean {
        val course = courseRepository.findByIdForUpdate(courseId).orElseThrow {
            IllegalStateException("Workspace 정책 초기화 대상 강의를 찾을 수 없습니다: $courseId")
        }
        if (course.workspacePolicyInitialized) return false

        val revision = course.workspacePolicyRevision + 1
        course.workspacePolicyRevision = revision
        course.workspacePolicyInitialized = true
        courseRepository.save(course)

        val shouldReconcile = course.status == CourseStatus.ACTIVE && course.workspaceRuntimeEnabled
        applyRevision(course, revision, enqueueReconcile = shouldReconcile, reason = null)
        return true
    }

    /**
     * Without a [reason] (one-time initialization) every route is revoked at once, as
     * before. For a regular bump only workspaces whose mounts change are restarted: their
     * open IDE pages get a restart notice and the access reconcile, which revokes the
     * route, waits [restartNoticeSeconds]. Workspaces whose mounts stay the same keep
     * their route, so their IDE is not interrupted at all.
     */
    private fun applyRevision(course: Course, revision: Long, enqueueReconcile: Boolean, reason: PolicyChangeReason?) {
        val now = LocalDateTime.now()
        jCodeRepository.findByCourseId(course.id)
            .filter {
                !it.snapshot &&
                    it.kind == JcodeKind.STANDARD &&
                    it.lifecycleStatus !in setOf(
                        JcodeLifecycleStatus.DELETE_PENDING,
                        JcodeLifecycleStatus.ARCHIVED
                    )
            }
            .forEach { jcode ->
                val mountsChange = reason == null || mountsChange(jcode)
                jcode.desiredRevision = revision
                jcode.desiredMountHash = null
                jCodeRepository.save(jcode)
                if (reason == null) redisService.deleteJcodeRoute(jcode.id)

                if (!enqueueReconcile) return@forEach

                if (jcode.lifecycleStatus in setOf(
                        JcodeLifecycleStatus.PROVISION_FAILED,
                        JcodeLifecycleStatus.DELETE_FAILED
                    )) {
                    redisService.deleteJcodeRoute(jcode.id)
                    jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
                    jCodeRepository.save(jcode)
                    operationRepository.save(
                        WorkspaceOperation(
                            targetType = WorkspaceOperationTarget.JCODE,
                            targetId = jcode.id,
                            action = WorkspaceOperationAction.DELETE_JCODE
                        )
                    )
                    return@forEach
                }
                val restartAt = if (reason != null && mountsChange) {
                    val at = now.plusSeconds(restartNoticeSeconds.coerceIn(0, 60))
                    if (jcode.lifecycleStatus == JcodeLifecycleStatus.READY) {
                        redisService.storeRestartNotice(
                            jcode.id,
                            "$revision",
                            reason,
                            Instant.now().plusSeconds(restartNoticeSeconds.coerceIn(0, 60))
                        )
                    }
                    at
                } else now
                val key = UUID.nameUUIDFromBytes(
                    "jcode-access:${jcode.id}:$revision".toByteArray(StandardCharsets.UTF_8)
                ).toString()
                if (!operationRepository.existsByIdempotencyKey(key)) {
                    operationRepository.save(
                        WorkspaceOperation(
                            targetType = WorkspaceOperationTarget.JCODE,
                            targetId = jcode.id,
                            action = WorkspaceOperationAction.RECONCILE_JCODE_ACCESS,
                            desiredRevision = revision,
                            idempotencyKey = key,
                            nextAttemptAt = restartAt
                        )
                    )
                }
            }
    }

    // Any doubt (no observed hash, policy unavailable) counts as a change, which keeps
    // the previous behaviour of revoking the route.
    private fun mountsChange(jcode: Jcode): Boolean {
        val policy = workspaceAccessPolicy ?: return true
        val observed = jcode.observedMountHash ?: return true
        if (jcode.lifecycleStatus != JcodeLifecycleStatus.READY) return true
        return runCatching { policy.plan(jcode).mountHash != observed }.getOrDefault(true)
    }
}
