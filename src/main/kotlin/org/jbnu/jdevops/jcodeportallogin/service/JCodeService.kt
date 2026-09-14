package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.dto.jcode.JCodeDto
import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.jbnu.jdevops.jcodeportallogin.repo.*
import org.jbnu.jdevops.jcodeportallogin.util.AuthorizationUtil
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.web.server.ResponseStatusException
import org.springframework.scheduling.annotation.Scheduled
import java.time.LocalDateTime
import java.util.UUID

@Service
class JCodeService(
    private val jCodeRepository: JCodeRepository,
    private val courseRepository: CourseRepository,
    private val userRepository: UserRepository,
    private val userCoursesRepository: UserCoursesRepository,
    private val assignmentRepository: AssignmentRepository,
    private val workspaceOperationStore: WorkspaceOperationStore,
    private val redisService: RedisService,
    private val workspaceAccessPolicy: WorkspaceAccessPolicy
) {
    // A waiter must read the committed instance after acquiring the membership lock.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createJCode(
        courseId: Long,
        userEmail: String,
        email: String,
        token: String,
        snapshot: Boolean,
        assignmentId: Long? = null
    ): JCodeDto {
        val course = courseRepository.findById(courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found") }
        if (course.status != CourseStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "종료된 강의에서는 JCode를 생성할 수 없습니다.")
        }
        if (!course.workspaceRuntimeEnabled) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "강의 실행 환경이 아직 준비되지 않았습니다.")
        }
        val actor = userRepository.findByEmail(email)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val target = userRepository.findByEmail(userEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "TargetUser not found")
        val membership = userCoursesRepository.findByUserIdAndCourseIdForUpdate(target.id, course.id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "TargetUserCourse not found")
        if (membership.lifecycleStatus != MembershipStatus.READY) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "학생 Workspace 준비가 완료되지 않았습니다.")
        }
        AuthorizationUtil.validateUserAuthority(actor.role, actor.id, target.id, course.id, userCoursesRepository)
        if (actor.id != target.id) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "다른 사용자의 JCode는 과제별 읽기 전용 검사 경로로만 생성할 수 있습니다."
            )
        }
        if (target.studentNum == null) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "PROFILE_INCOMPLETE",
                "JCode를 실행하려면 먼저 사용자 정보를 완료해주세요."
            )
        }
        if (snapshot) {
            AuthorizationUtil.validateUserAuthority(actor.role, actor.id, 0, course.id, userCoursesRepository)
        }

        val assignment = if (course.workspaceScope == WorkspaceScope.ASSIGNMENT && !snapshot) {
            val id = assignmentId
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "과제 단위 환경은 assignmentId가 필요합니다.")
            assignmentRepository.findByIdAndCourseId(id, course.id)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found in course") }
                .also {
                    if (!workspaceAccessPolicy.isStudentAccessible(it)) {
                        throw ResponseStatusException(HttpStatus.CONFLICT, "현재 열려 있는 과제만 IDE에 진입할 수 있습니다.")
                    }
                }
        } else null

        val instanceKey = "${membership.id}:${assignment?.id ?: 0}:$snapshot"
        val existing = jCodeRepository.findActiveInstanceForUpdate(instanceKey)
        if (existing?.lifecycleStatus in setOf(JcodeLifecycleStatus.DELETE_PENDING, JcodeLifecycleStatus.DELETE_FAILED)) {
            throw PublicApiException(HttpStatus.CONFLICT, "JCODE_DELETING", "기존 실행 환경을 정리하고 있습니다. 정리가 끝난 뒤 다시 실행해주세요.")
        }
        if (existing?.lifecycleStatus == JcodeLifecycleStatus.PROVISION_FAILED) {
            workspaceOperationStore.retry(WorkspaceOperationTarget.JCODE, existing.id)
        }
        if (existing != null && existing.lifecycleStatus != JcodeLifecycleStatus.ARCHIVED) {
            return existing.toDto()
        }

        val suffix = assignment?.let { "-${it.id}" } ?: ""
        val deploymentName = if (snapshot) {
            "jcode-snapshot-${course.infrastructureKey.lowercase()}-${target.studentNum}$suffix"
        } else {
            "jcode-${course.infrastructureKey.lowercase()}-${course.clss}-${target.studentNum}$suffix"
        }
        val jcode = jCodeRepository.saveAndFlush(
            Jcode(
                userCourse = membership,
                course = course,
                user = target,
                assignment = assignment,
                instanceKey = instanceKey,
                snapshot = snapshot,
                deploymentName = deploymentName,
                serviceName = "$deploymentName-svc",
                lifecycleStatus = JcodeLifecycleStatus.PROVISIONING,
                desiredRevision = course.workspacePolicyRevision
            )
        )
        workspaceOperationStore.enqueue(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE,
            desiredRevision = jcode.desiredRevision
        )
        return jcode.toDto()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun ensureInspector(actorEmail: String, targetEmail: String, courseId: Long, assignmentId: Long): Jcode {
        val actor = userRepository.findByEmail(actorEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Current user not found")
        val target = userRepository.findByEmail(targetEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Target user not found")
        val course = courseRepository.findById(courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found") }
        if (course.status != CourseStatus.ACTIVE || !course.workspaceRuntimeEnabled) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "강의 실행 환경을 사용할 수 없습니다.")
        }
        // Self-preview is still a manager operation, never the ordinary owner bypass.
        AuthorizationUtil.validateUserAuthority(actor.role, actor.id, 0, course.id, userCoursesRepository)
        val membership = userCoursesRepository.findByUserIdAndCourseIdForUpdate(target.id, course.id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "TargetUserCourse not found")
        if (membership.lifecycleStatus != MembershipStatus.READY ||
            (membership.role != RoleType.STUDENT && actor.id != target.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "READY 상태의 학생 과제만 검사할 수 있습니다.")
        }
        if (target.studentNum == null) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "PROFILE_INCOMPLETE",
                "대상 학생의 사용자 정보가 완료되지 않았습니다."
            )
        }
        val assignment = assignmentRepository.findByIdAndCourseIdForUpdate(assignmentId, course.id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found in course") }
        if (assignment.lifecycleStatus != AssignmentLifecycleStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "활성 과제만 검사할 수 있습니다.")
        }
        if (assignment.starterDistributionPending) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "STARTER_DISTRIBUTION_PENDING",
                "스타터 코드 배포가 완료된 후 학생 과제를 검사할 수 있습니다."
            )
        }
        val now = LocalDateTime.now()
        val existing = jCodeRepository.findInspectorsForUpdate(target.id, course.id, assignment.id).firstOrNull()
        if (
            existing != null &&
            existing.expiresAt?.isAfter(now) == true &&
            existing.lifecycleStatus in setOf(JcodeLifecycleStatus.PROVISIONING, JcodeLifecycleStatus.READY)
        ) return existing
        if (existing != null) enqueueDelete(existing)

        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val deploymentName = "jinspect-${course.id}-${membership.id}-${assignment.id}-$suffix".take(59)
        val inspector = jCodeRepository.saveAndFlush(
            Jcode(
                userCourse = membership,
                course = course,
                user = target,
                assignment = assignment,
                instanceKey = "inspect:${actor.id}:${membership.id}:${assignment.id}:$suffix",
                kind = JcodeKind.INSPECTOR,
                deploymentName = deploymentName,
                serviceName = "$deploymentName-svc",
                lifecycleStatus = JcodeLifecycleStatus.PROVISIONING,
                desiredRevision = course.workspacePolicyRevision,
                expiresAt = now.plusMinutes(30)
            )
        )
        workspaceOperationStore.enqueue(
            WorkspaceOperationTarget.JCODE,
            inspector.id,
            WorkspaceOperationAction.PROVISION_JCODE,
            desiredRevision = inspector.desiredRevision
        )
        return inspector
    }

    @Scheduled(fixedDelayString = "\${jcode.inspector.cleanup-ms:60000}")
    @Transactional
    fun expireInspectors() {
        jCodeRepository.findByKindAndExpiresAtBeforeAndLifecycleStatusNot(
            JcodeKind.INSPECTOR,
            LocalDateTime.now(),
            JcodeLifecycleStatus.ARCHIVED
        ).forEach(::enqueueDelete)
    }

    @Transactional
    fun expireStudentOnlySessions(membership: UserCourses) {
        jCodeRepository.findAllByUserCourse(membership)
            .filter {
                it.kind == JcodeKind.INSPECTOR ||
                    (it.kind == JcodeKind.STANDARD && it.assignment != null)
            }
            .forEach(::enqueueDelete)
    }

    @Transactional
    fun expireManagerOnlySessions(membership: UserCourses) {
        jCodeRepository.findAllByUserCourse(membership)
            .filter { it.kind in setOf(JcodeKind.SNAPSHOT, JcodeKind.INSPECTOR) }
            .forEach(::enqueueDelete)
    }

    @Transactional
    fun deleteJCode(userEmail: String, courseId: Long, token: String, snapshot: Boolean, assignmentId: Long? = null) {
        val user = userRepository.findByEmail(userEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val jcode = if (assignmentId == null) {
            jCodeRepository.findFirstByUserIdAndCourseIdAndSnapshotAndAssignmentIsNullAndLifecycleStatusNotOrderByIdDesc(
                user.id, courseId, snapshot, JcodeLifecycleStatus.ARCHIVED
            )
        } else {
            jCodeRepository.findFirstByUserIdAndCourseIdAndAssignmentIdAndKindAndLifecycleStatusNotOrderByIdDesc(
                user.id, courseId, assignmentId, JcodeKind.STANDARD, JcodeLifecycleStatus.ARCHIVED
            )
        }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "JCode not found for the specified user and course")
        enqueueDelete(jcode)
    }

    @Transactional
    fun deleteAllJCodesForUserCourse(userCourse: UserCourses, token: String) {
        jCodeRepository.findAllByUserCourse(userCourse).forEach(::enqueueDelete)
    }

    private fun enqueueDelete(jcode: Jcode) {
        if (jcode.lifecycleStatus in setOf(JcodeLifecycleStatus.DELETE_PENDING, JcodeLifecycleStatus.ARCHIVED)) return
        jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
        jcode.lastError = null
        jCodeRepository.save(jcode)
        redisService.deleteJcodeRoute(jcode.id)
        workspaceOperationStore.enqueue(
            WorkspaceOperationTarget.JCODE, jcode.id, WorkspaceOperationAction.DELETE_JCODE
        )
    }

    @Transactional
    fun retryJCode(actorEmail: String, userEmail: String, courseId: Long, snapshot: Boolean, assignmentId: Long? = null) {
        val actor = userRepository.findByEmail(actorEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Current user not found")
        val user = userRepository.findByEmail(userEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        AuthorizationUtil.validateUserAuthority(actor.role, actor.id, user.id, courseId, userCoursesRepository)
        val jcode = if (assignmentId == null) {
            jCodeRepository.findFirstByUserIdAndCourseIdAndSnapshotAndAssignmentIsNullAndLifecycleStatusNotOrderByIdDesc(
                user.id, courseId, snapshot, JcodeLifecycleStatus.ARCHIVED
            )
        } else {
            jCodeRepository.findFirstByUserIdAndCourseIdAndAssignmentIdAndKindAndLifecycleStatusNotOrderByIdDesc(
                user.id, courseId, assignmentId, JcodeKind.STANDARD, JcodeLifecycleStatus.ARCHIVED
            )
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "JCode not found")
        val runtimeReconcileFailed = jcode.lifecycleStatus == JcodeLifecycleStatus.READY &&
            jcode.observedStatus == JcodeObservedStatus.FAILED
        if (!runtimeReconcileFailed &&
            jcode.lifecycleStatus !in setOf(JcodeLifecycleStatus.PROVISION_FAILED, JcodeLifecycleStatus.DELETE_FAILED)
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "현재 JCode에 실패한 작업이 없습니다.")
        }
        if (!workspaceOperationStore.retry(WorkspaceOperationTarget.JCODE, jcode.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "재시도할 실패 작업이 없습니다.")
        }
    }

    private fun Jcode.toDto() = JCodeDto(
        jcodeId = id,
        courseName = course.name,
        status = lifecycleStatus,
        observedStatus = observedStatus,
        observedReason = observedReason,
        lastObservedAt = lastObservedAt,
        jcodeUrl = jcodeUrl,
        assignmentId = assignment?.id,
        lastError = lastError?.let {
            "JCode 환경 처리 중 오류가 발생했습니다. 잠시 후 재시도하거나 관리자에게 문의해주세요."
        }
    )
}
