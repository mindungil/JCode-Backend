package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.WorkspaceOperationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.UUID

@Service
class WorkspacePolicyRevisionService(
    private val courseRepository: CourseRepository,
    private val jCodeRepository: JCodeRepository,
    private val operationRepository: WorkspaceOperationRepository,
    private val redisService: RedisService
) {
    @Transactional
    fun bump(courseId: Long): Long {
        val course = courseRepository.findByIdForUpdate(courseId).orElseThrow {
            IllegalStateException("Workspace 정책 대상 강의를 찾을 수 없습니다: $courseId")
        }
        val revision = course.workspacePolicyRevision + 1
        course.workspacePolicyRevision = revision
        courseRepository.save(course)

        applyRevision(course, revision, enqueueReconcile = true)
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
        applyRevision(course, revision, enqueueReconcile = shouldReconcile)
        return true
    }

    private fun applyRevision(course: Course, revision: Long, enqueueReconcile: Boolean) {
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
                jcode.desiredRevision = revision
                jcode.desiredMountHash = null
                jCodeRepository.save(jcode)
                redisService.deleteJcodeRoute(jcode.id)

                if (!enqueueReconcile) return@forEach

                if (jcode.lifecycleStatus in setOf(
                        JcodeLifecycleStatus.PROVISION_FAILED,
                        JcodeLifecycleStatus.DELETE_FAILED
                    )) {
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
                            idempotencyKey = key
                        )
                    )
                }
            }
    }
}
