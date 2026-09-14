package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.jbnu.jdevops.jcodeportallogin.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

data class StarterUploadReservation(
    val courseId: Long,
    val namespace: String,
    val assignmentId: Long,
    val artifactId: Long,
    val version: Int,
    val artifactKey: String,
    val reservedInitialUpload: Boolean
)

data class StarterUploadCompletion(
    val assignment: Assignment,
    val rejectionCode: String? = null,
    val rejectionMessage: String? = null
)

@Service
class StarterUploadStateService(
    private val assignmentRepository: AssignmentRepository,
    private val courseRepository: CourseRepository,
    private val userRepository: UserRepository,
    private val userCoursesRepository: UserCoursesRepository,
    private val starterArtifactRepository: StarterArtifactRepository,
    private val jCodeRepository: JCodeRepository,
    private val workspaceOperationStore: WorkspaceOperationStore,
    private val workspacePolicyRevisionService: WorkspacePolicyRevisionService,
    private val redisService: RedisService,
    @Value("\${assignment.starter-upload-timeout-minutes:10}")
    private val uploadTimeoutMinutes: Long
) {
    @Transactional
    fun reserve(
        courseId: Long,
        assignmentId: Long,
        overwritePolicy: StarterOverwritePolicy,
        deployNow: Boolean,
        email: String
    ): StarterUploadReservation {
        if (!deployNow) {
            throw PublicApiException(
                HttpStatus.BAD_REQUEST,
                "STARTER_DEPLOY_REQUIRED",
                "스타터 코드는 저장과 배포를 함께 요청해야 합니다."
            )
        }
        validateAssignmentAuthority(courseId, email)
        val course = courseRepository.findById(courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found") }
        if (course.status != CourseStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "종료 중이거나 종료된 강의는 수정할 수 없습니다.")
        }
        val assignment = assignmentRepository.findByIdAndCourseIdForUpdate(assignmentId, courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found in course") }
        if (assignment.lifecycleStatus !in setOf(
                AssignmentLifecycleStatus.PROVISIONING,
                AssignmentLifecycleStatus.ACTIVE
            )
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "준비 중이거나 활성 상태인 과제에만 스타터 코드를 올릴 수 있습니다.")
        }
        if (!LocalDateTime.now().isBefore(assignment.deadlineDate) ||
            assignment.scheduleStatus in setOf(AssignmentScheduleStatus.CLOSED, AssignmentScheduleStatus.ARCHIVED)
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "마감되거나 보관된 과제에는 스타터 코드를 배포할 수 없습니다.")
        }
        val reservedInitialUpload = assignment.starterDistributionPending && !assignment.hasStarterCode
        if (assignment.starterDistributionPending && !reservedInitialUpload) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "STARTER_DISTRIBUTION_PENDING",
                "이전 스타터 코드 배포가 진행 중입니다. 완료하거나 재시도한 뒤 다시 요청해주세요."
            )
        }
        if (reservedInitialUpload && !deployNow) {
            throw PublicApiException(
                HttpStatus.BAD_REQUEST,
                "INITIAL_STARTER_DEPLOY_REQUIRED",
                "스타터 코드를 포함해 만든 과제는 업로드와 함께 배포해야 합니다."
            )
        }

        starterArtifactRepository.findTopByAssignmentIdAndStatusOrderByVersionDesc(
            assignment.id,
            StarterArtifactStatus.UPLOADING
        )?.let { uploading ->
            if (!uploading.uploadedAt.isBefore(LocalDateTime.now().minusMinutes(uploadTimeoutMinutes))) {
                throw PublicApiException(
                    HttpStatus.CONFLICT,
                    "STARTER_UPLOAD_PENDING",
                    "같은 과제의 스타터 코드 업로드가 이미 진행 중입니다."
                )
            }
            uploading.status = StarterArtifactStatus.FAILED
            uploading.lastError = "STARTER_UPLOAD_TIMEOUT"
            starterArtifactRepository.save(uploading)
        }

        val version = (starterArtifactRepository.findTopByAssignmentIdOrderByVersionDesc(assignment.id)?.version ?: 0) + 1
        val artifact = starterArtifactRepository.save(
            StarterArtifact(
                assignment = assignment,
                version = version,
                artifactKey = "assignments/${assignment.id}/starter/v$version.zip",
                overwritePolicy = overwritePolicy
            )
        )
        return StarterUploadReservation(
            courseId = course.id,
            namespace = course.namespaceKey ?: Course.namespaceKey(course.infrastructureKey, course.clss),
            assignmentId = assignment.id,
            artifactId = artifact.id,
            version = version,
            artifactKey = artifact.artifactKey,
            reservedInitialUpload = reservedInitialUpload
        )
    }

    @Transactional
    fun markUploadFailed(reservation: StarterUploadReservation, error: Throwable) {
        val artifact = starterArtifactRepository.findById(reservation.artifactId).orElse(null) ?: return
        if (artifact.status != StarterArtifactStatus.UPLOADING) return
        artifact.status = StarterArtifactStatus.FAILED
        artifact.lastError = (error.message ?: error.javaClass.simpleName).take(4000)
        starterArtifactRepository.save(artifact)

        if (reservation.reservedInitialUpload) {
            assignmentRepository.findByIdAndCourseIdForUpdate(reservation.assignmentId, reservation.courseId)
                .ifPresent { assignment ->
                    if (assignment.course.status == CourseStatus.ACTIVE &&
                        assignment.lifecycleStatus in setOf(
                            AssignmentLifecycleStatus.PROVISIONING,
                            AssignmentLifecycleStatus.ACTIVE
                        ) &&
                        assignment.scheduleStatus !in setOf(
                            AssignmentScheduleStatus.CLOSED,
                            AssignmentScheduleStatus.ARCHIVED
                        ) &&
                        assignment.starterDistributionPending &&
                        !assignment.hasStarterCode
                    ) {
                        assignment.lastError = "STARTER_UPLOAD_FAILED"
                        assignmentRepository.save(assignment)
                    }
                }
        }
    }

    @Transactional
    fun complete(
        reservation: StarterUploadReservation,
        checksum: String,
        sizeBytes: Long,
        deployNow: Boolean
    ): StarterUploadCompletion {
        val assignment = assignmentRepository.findByIdAndCourseIdForUpdate(
            reservation.assignmentId,
            reservation.courseId
        ).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found in course") }
        val artifact = starterArtifactRepository.findById(reservation.artifactId)
            .orElseThrow { IllegalStateException("스타터 artifact 예약을 찾을 수 없습니다.") }
        if (artifact.status != StarterArtifactStatus.UPLOADING) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "STARTER_UPLOAD_EXPIRED",
                "스타터 코드 업로드 예약이 만료되었습니다. 다시 업로드해주세요."
            )
        }
        artifact.checksum = checksum
        artifact.sizeBytes = sizeBytes
        artifact.lastError = null

        val stillEligible = assignment.course.status == CourseStatus.ACTIVE &&
            assignment.lifecycleStatus in setOf(
                AssignmentLifecycleStatus.PROVISIONING,
                AssignmentLifecycleStatus.ACTIVE
            ) &&
            assignment.scheduleStatus !in setOf(
                AssignmentScheduleStatus.CLOSED,
                AssignmentScheduleStatus.ARCHIVED
            ) &&
            LocalDateTime.now().isBefore(assignment.deadlineDate)
        if (!stillEligible) {
            artifact.status = StarterArtifactStatus.ARCHIVED
            artifact.lastError = "ASSIGNMENT_CHANGED_DURING_STARTER_UPLOAD"
            starterArtifactRepository.save(artifact)
            return StarterUploadCompletion(
                assignment,
                "ASSIGNMENT_CHANGED_DURING_STARTER_UPLOAD",
                "업로드 중 과제 상태가 변경되어 스타터 코드를 배포하지 않았습니다."
            )
        }

        artifact.status = StarterArtifactStatus.READY
        starterArtifactRepository.save(artifact)
        assignment.hasStarterCode = true
        assignment.lastError = null
        assignmentRepository.save(assignment)

        if (deployNow) {
            closeAssignmentSessions(assignment.id)
            val revision = if (reservation.reservedInitialUpload) {
                assignment.course.workspacePolicyRevision
            } else {
                assignment.starterDistributionPending = true
                assignmentRepository.save(assignment)
                workspacePolicyRevisionService.bump(assignment.course.id)
            }
            workspaceOperationStore.enqueue(
                WorkspaceOperationTarget.ASSIGNMENT,
                assignment.id,
                WorkspaceOperationAction.DISTRIBUTE_STARTER,
                artifact.id,
                revision
            )
        }
        return StarterUploadCompletion(assignment)
    }

    private fun closeAssignmentSessions(assignmentId: Long) {
        jCodeRepository.findByAssignmentId(assignmentId)
            .forEach { jcode ->
                if (jcode.lifecycleStatus in setOf(JcodeLifecycleStatus.DELETE_PENDING, JcodeLifecycleStatus.ARCHIVED)) {
                    return@forEach
                }
                jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
                jcode.lastError = null
                jCodeRepository.save(jcode)
                redisService.deleteJcodeRoute(jcode.id)
                workspaceOperationStore.enqueue(
                    WorkspaceOperationTarget.JCODE,
                    jcode.id,
                    WorkspaceOperationAction.DELETE_JCODE
                )
            }
    }

    private fun validateAssignmentAuthority(courseId: Long, email: String) {
        val user = userRepository.findByEmail(email)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        if (user.role == RoleType.ADMIN) return
        val membership = userCoursesRepository.findByUserIdAndCourseId(user.id, courseId)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 강의에 소속되어 있지 않습니다.")
        if (membership.lifecycleStatus != MembershipStatus.READY ||
            membership.role !in setOf(RoleType.PROFESSOR, RoleType.ASSISTANT)
        ) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 강의의 과제 관리 권한이 없습니다.")
        }
    }
}
