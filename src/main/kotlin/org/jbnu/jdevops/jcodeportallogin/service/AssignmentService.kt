package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.config.GENERATOR_SCOPE_ATTRIBUTE
import org.jbnu.jdevops.jcodeportallogin.dto.assignment.AssignmentDto
import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.jbnu.jdevops.jcodeportallogin.repo.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime

@Service
class AssignmentService(
    private val assignmentRepository: AssignmentRepository,
    private val courseRepository: CourseRepository,
    private val userRepository: UserRepository,
    private val userCoursesRepository: UserCoursesRepository,
    private val starterArtifactRepository: StarterArtifactRepository,
    private val jCodeRepository: JCodeRepository,
    private val workspaceOperationStore: WorkspaceOperationStore,
    private val workspacePolicyRevisionService: WorkspacePolicyRevisionService,
    private val redisService: RedisService,
    private val generatorContractVerifier: GeneratorContractVerifier,
    private val starterUploadStateService: StarterUploadStateService,
    @Qualifier("generatorWorkspaceWebClient") private val generatorWebClient: WebClient,
    @Value("\${assignment.starter-upload-timeout-minutes:10}")
    private val starterUploadTimeoutMinutes: Long,
    @Value("\${assignment.starter-upload-request-timeout-seconds:120}")
    private val starterUploadRequestTimeoutSeconds: Long
) {
    private fun validateAssignmentAuthority(courseId: Long, email: String) {
        val user = userRepository.findByEmail(email)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        if (user.role == RoleType.ADMIN) return
        val membership = userCoursesRepository.findByUserIdAndCourseId(user.id, courseId)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 강의에 소속되어 있지 않습니다.")
        if (membership.lifecycleStatus != MembershipStatus.READY) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "활성 상태의 강의 소속만 과제를 관리할 수 있습니다.")
        }
        if (membership.role !in setOf(RoleType.PROFESSOR, RoleType.ASSISTANT)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 강의의 과제 관리 권한이 없습니다.")
        }
    }

    private fun getActiveCourse(courseId: Long): Course {
        val course = courseRepository.findById(courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found") }
        if (course.status != CourseStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "ACTIVE 상태의 강의에서만 과제를 변경할 수 있습니다.")
        }
        return course
    }

    private fun getAssignment(courseId: Long, assignmentId: Long): Assignment =
        assignmentRepository.findByIdAndCourseId(assignmentId, courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found in course") }

    private fun getAssignmentForUpdate(courseId: Long, assignmentId: Long): Assignment =
        assignmentRepository.findByIdAndCourseIdForUpdate(assignmentId, courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found in course") }

    private fun validateDates(kickoff: LocalDateTime, deadline: LocalDateTime) {
        if (!deadline.isAfter(kickoff)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "마감 시각은 시작 시각보다 뒤여야 합니다.")
        }
    }

    private fun scheduleStatus(kickoff: LocalDateTime, deadline: LocalDateTime): AssignmentScheduleStatus {
        val now = LocalDateTime.now()
        return when {
            now.isBefore(kickoff) -> AssignmentScheduleStatus.SCHEDULED
            !now.isBefore(deadline) -> AssignmentScheduleStatus.CLOSED
            else -> AssignmentScheduleStatus.OPEN
        }
    }

    private fun closeAssignmentJcodes(assignmentId: Long, includeInspectors: Boolean = true) {
        jCodeRepository.findByAssignmentId(assignmentId).forEach { jcode ->
            if (!includeInspectors && jcode.kind == JcodeKind.INSPECTOR) return@forEach
            if (jcode.lifecycleStatus !in setOf(JcodeLifecycleStatus.DELETE_PENDING, JcodeLifecycleStatus.ARCHIVED)) {
                jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
                jcode.lastError = null
                jCodeRepository.save(jcode)
                redisService.deleteJcodeRoute(jcode.id)
                workspaceOperationStore.enqueue(
                    WorkspaceOperationTarget.JCODE, jcode.id, WorkspaceOperationAction.DELETE_JCODE
                )
            }
        }
    }

    fun toDto(assignment: Assignment): AssignmentDto {
        val starter = starterArtifactRepository.findTopByAssignmentIdAndStatusOrderByVersionDesc(
            assignment.id, StarterArtifactStatus.READY
        )
        return AssignmentDto(
            assignmentId = assignment.id,
            assignmentName = assignment.name,
            assignmentDescription = assignment.description,
            dirName = assignment.workspaceKey,
            workspaceKey = assignment.workspaceKey,
            hasStarterCode = assignment.hasStarterCode,
            starterCodeExpected = false,
            starterDistributionPending = assignment.starterDistributionPending,
            lifecycleStatus = assignment.lifecycleStatus,
            scheduleStatus = assignment.scheduleStatus,
            lastError = assignment.lastError?.let {
                "과제 환경 처리 중 오류가 발생했습니다. 잠시 후 재시도하거나 관리자에게 문의해주세요."
            },
            starterVersion = starter?.version,
            starterChecksum = starter?.checksum,
            starterOverwritePolicy = starter?.overwritePolicy,
            archiveRetentionDays = assignment.archiveRetentionDays,
            archivedAt = assignment.archivedAt?.toString(),
            finalizedAt = assignment.finalizedAt?.toString(),
            kickoffDate = assignment.kickoffDate,
            deadlineDate = assignment.deadlineDate,
            createdAt = assignment.createdAt.toString(),
            updatedAt = assignment.updatedAt.toString()
        )
    }

    @Transactional
    fun createAssignment(courseId: Long, dto: AssignmentDto, email: String, token: String): AssignmentDto {
        validateAssignmentAuthority(courseId, email)
        val course = getActiveCourse(courseId)
        validateDates(dto.kickoffDate, dto.deadlineDate)
        if (!dto.deadlineDate.isAfter(LocalDateTime.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "새 과제의 마감 시각은 현재보다 뒤여야 합니다.")
        }
        val assignmentName = dto.assignmentName.trim()
        if (assignmentRepository.existsByCourseIdAndName(course.id, assignmentName)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Assignment already exists")
        }
        val assignment = assignmentRepository.saveAndFlush(
            Assignment(
                name = assignmentName,
                description = dto.assignmentDescription,
                kickoffDate = dto.kickoffDate,
                deadlineDate = dto.deadlineDate,
                scheduleStatus = scheduleStatus(dto.kickoffDate, dto.deadlineDate),
                starterDistributionPending = dto.starterCodeExpected,
                archiveRetentionDays = (dto.archiveRetentionDays ?: 90).coerceIn(1, 3650),
                course = course
            )
        )
        assignment.workspaceKey = "assignment-${assignment.id}"
        assignment.dirName = assignment.workspaceKey
        assignmentRepository.save(assignment)
        workspaceOperationStore.enqueue(
            WorkspaceOperationTarget.ASSIGNMENT, assignment.id, WorkspaceOperationAction.PROVISION_ASSIGNMENT
        )
        return toDto(assignment)
    }

    @Transactional
    fun updateAssignment(courseId: Long, assignmentId: Long, dto: AssignmentDto, email: String): AssignmentDto {
        validateAssignmentAuthority(courseId, email)
        getActiveCourse(courseId)
        val assignment = getAssignmentForUpdate(courseId, assignmentId)
        if (assignment.lifecycleStatus in setOf(AssignmentLifecycleStatus.DELETING, AssignmentLifecycleStatus.ARCHIVED)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "삭제 중이거나 보관된 과제는 수정할 수 없습니다.")
        }
        if (assignment.lifecycleStatus == AssignmentLifecycleStatus.PROVISIONING && assignment.finalizedAt != null) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "ASSIGNMENT_RESTORE_PENDING",
                "과제 복원이 진행 중입니다. 복원이 완료된 후 다시 수정해주세요."
            )
        }
        validateDates(dto.kickoffDate, dto.deadlineDate)
        val currentSchedule = scheduleStatus(assignment.kickoffDate, assignment.deadlineDate)
        if (currentSchedule == AssignmentScheduleStatus.CLOSED) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "ASSIGNMENT_REOPEN_REQUIRED",
                "마감된 과제는 최종본 보관 후 다시 열어 변경할 수 있습니다."
            )
        }
        val assignmentName = dto.assignmentName.trim()
        if (assignmentRepository.existsByCourseIdAndNameAndIdNot(courseId, assignmentName, assignmentId)) {
            throw PublicApiException(HttpStatus.CONFLICT, "ASSIGNMENT_NAME_CONFLICT", "같은 강의에 동일한 과제명이 이미 있습니다.")
        }
        val requestedSchedule = scheduleStatus(dto.kickoffDate, dto.deadlineDate)
        val closing = currentSchedule != AssignmentScheduleStatus.CLOSED &&
            requestedSchedule == AssignmentScheduleStatus.CLOSED
        val suspending = currentSchedule == AssignmentScheduleStatus.OPEN &&
            requestedSchedule == AssignmentScheduleStatus.SCHEDULED
        val accessPolicyChanged = assignment.kickoffDate != dto.kickoffDate ||
            assignment.deadlineDate != dto.deadlineDate ||
            assignment.scheduleStatus != requestedSchedule
        val updated = assignment.copy(
            name = assignmentName,
            description = dto.assignmentDescription,
            kickoffDate = dto.kickoffDate,
            deadlineDate = dto.deadlineDate,
            scheduleStatus = requestedSchedule,
            archiveRetentionDays = (dto.archiveRetentionDays ?: assignment.archiveRetentionDays).coerceIn(1, 3650),
            finalizationGeneration = if (closing) {
                assignment.finalizationGeneration + 1
            } else assignment.finalizationGeneration,
            starterDistributionPending = if (closing && !assignment.hasStarterCode) {
                false
            } else assignment.starterDistributionPending,
            lastError = if (closing && !assignment.hasStarterCode) null else assignment.lastError,
            updatedAt = LocalDateTime.now()
        )
        val saved = assignmentRepository.save(updated)
        workspaceOperationStore.enqueue(
            WorkspaceOperationTarget.ASSIGNMENT, saved.id, WorkspaceOperationAction.UPDATE_ASSIGNMENT_METADATA
        )
        if (accessPolicyChanged) workspacePolicyRevisionService.bump(saved.course.id)
        if (closing || suspending) {
            closeAssignmentJcodes(saved.id, includeInspectors = false)
        }
        if (closing) {
            workspaceOperationStore.enqueue(
                WorkspaceOperationTarget.ASSIGNMENT, saved.id, WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
            )
        }
        return toDto(saved)
    }

    fun uploadStarterCode(
        courseId: Long,
        assignmentId: Long,
        file: MultipartFile,
        overwritePolicy: StarterOverwritePolicy,
        deployNow: Boolean,
        email: String,
        token: String
    ): AssignmentDto {
        val reservation = starterUploadStateService.reserve(
            courseId,
            assignmentId,
            overwritePolicy,
            deployNow,
            email
        )
        val result: Map<*, *>
        try {
            generatorContractVerifier.requireCompatible()
            result = generatorWebClient.post()
                .uri("/api/workspace/assignments/starter/upload")
                .attribute(GENERATOR_SCOPE_ATTRIBUTE, "workspace:write")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(LinkedMultiValueMap<String, Any>().apply {
                    add("course_id", reservation.courseId)
                    add("namespace", reservation.namespace)
                    add("assignment_id", reservation.assignmentId)
                    add("version", reservation.version)
                    add("artifact_key", reservation.artifactKey)
                    add("file", object : ByteArrayResource(file.bytes) {
                        override fun getFilename(): String = file.originalFilename ?: "starter.zip"
                    })
                })
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(Duration.ofSeconds(starterUploadRequestTimeoutSeconds))
                .block() ?: throw IllegalStateException("Generator 응답이 없습니다.")
        } catch (error: Exception) {
            starterUploadStateService.markUploadFailed(reservation, error)
            throw PublicApiException(
                HttpStatus.BAD_GATEWAY,
                "STARTER_UPLOAD_FAILED",
                "스타터 코드 저장에 실패했습니다. 잠시 후 다시 시도해주세요."
            )
        }

        val checksum = result["checksum"] as? String
            ?: run {
                val error = IllegalStateException("Generator가 checksum을 반환하지 않았습니다.")
                starterUploadStateService.markUploadFailed(reservation, error)
                throw PublicApiException(
                    HttpStatus.BAD_GATEWAY,
                    "STARTER_UPLOAD_INVALID_RESPONSE",
                    "스타터 코드 저장 응답을 확인할 수 없습니다. 다시 시도해주세요."
                )
            }
        val completion = starterUploadStateService.complete(
            reservation,
            checksum,
            (result["size_bytes"] as? Number)?.toLong() ?: file.size,
            deployNow
        )
        if (completion.rejectionCode != null) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                completion.rejectionCode,
                completion.rejectionMessage ?: "과제 상태가 변경되어 스타터 코드를 배포하지 않았습니다."
            )
        }
        return toDto(completion.assignment)
    }

    @Transactional
    fun deleteAssignment(courseId: Long, assignmentId: Long, email: String, retentionDays: Int = 90) {
        validateAssignmentAuthority(courseId, email)
        getActiveCourse(courseId)
        val assignment = getAssignmentForUpdate(courseId, assignmentId)
        if (assignment.lifecycleStatus == AssignmentLifecycleStatus.ARCHIVED) return
        if (assignment.lifecycleStatus == AssignmentLifecycleStatus.DELETING) return
        assignment.lifecycleStatus = AssignmentLifecycleStatus.DELETING
        assignment.archiveRetentionDays = retentionDays.coerceIn(1, 3650)
        assignment.lastError = null
        assignmentRepository.save(assignment)
        closeAssignmentJcodes(assignment.id)
        workspacePolicyRevisionService.bump(assignment.course.id)
        workspaceOperationStore.enqueue(
            WorkspaceOperationTarget.ASSIGNMENT, assignment.id, WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
        )
    }

    @Transactional
    fun reopenAssignment(courseId: Long, assignmentId: Long, newDeadline: LocalDateTime, email: String): AssignmentDto {
        validateAssignmentAuthority(courseId, email)
        getActiveCourse(courseId)
        val assignment = getAssignmentForUpdate(courseId, assignmentId)
        if (assignment.lifecycleStatus != AssignmentLifecycleStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "ACTIVE 상태의 과제만 다시 열 수 있습니다.")
        }
        if (!newDeadline.isAfter(LocalDateTime.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "새 마감 시각은 현재보다 뒤여야 합니다.")
        }
        if (assignment.scheduleStatus != AssignmentScheduleStatus.CLOSED || assignment.finalizedAt == null) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                "ASSIGNMENT_FINALIZATION_PENDING",
                "최종 작업물 보관이 완료된 후 과제를 다시 열 수 있습니다."
            )
        }
        if (assignment.finalizedAt!!.plusDays(assignment.archiveRetentionDays.toLong()).isBefore(LocalDateTime.now())) {
            throw PublicApiException(
                HttpStatus.GONE,
                "ASSIGNMENT_REOPEN_EXPIRED",
                "최종 작업물 보관 기간이 지나 과제를 다시 열 수 없습니다."
            )
        }
        val updated = assignment.copy(
            deadlineDate = newDeadline,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING,
            updatedAt = LocalDateTime.now()
        )
        val saved = assignmentRepository.save(updated)
        workspaceOperationStore.enqueue(
            WorkspaceOperationTarget.ASSIGNMENT, saved.id, WorkspaceOperationAction.RESTORE_ASSIGNMENT
        )
        return toDto(saved)
    }

    fun retryAssignment(courseId: Long, assignmentId: Long, email: String) {
        validateAssignmentAuthority(courseId, email)
        val assignment = getAssignment(courseId, assignmentId)
        if (assignment.lifecycleStatus != AssignmentLifecycleStatus.PROVISION_FAILED && assignment.lastError.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "현재 과제에 실패한 작업이 없습니다.")
        }
        if (!workspaceOperationStore.retry(WorkspaceOperationTarget.ASSIGNMENT, assignmentId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "재시도할 실패 작업이 없습니다.")
        }
    }

    @Scheduled(fixedDelayString = "\${assignment.schedule.refresh-ms:30000}")
    @Transactional
    fun refreshScheduleStatuses() {
        val now = LocalDateTime.now()
        val changedCourseIds = mutableSetOf<Long>()
        assignmentRepository.findExpiredStarterUploadReservationsForUpdate(
            AssignmentLifecycleStatus.ACTIVE,
            StarterArtifactStatus.UPLOADING,
            now.minusMinutes(starterUploadTimeoutMinutes)
        ).forEach { assignment ->
            starterArtifactRepository.findTopByAssignmentIdAndStatusOrderByVersionDesc(
                assignment.id,
                StarterArtifactStatus.UPLOADING
            )?.let { artifact ->
                if (!artifact.uploadedAt.isAfter(now.minusMinutes(starterUploadTimeoutMinutes))) {
                    artifact.status = StarterArtifactStatus.FAILED
                    artifact.lastError = "STARTER_UPLOAD_TIMEOUT"
                    starterArtifactRepository.save(artifact)
                }
            }
            assignment.lastError = "STARTER_UPLOAD_TIMEOUT"
            assignment.updatedAt = now
            assignmentRepository.save(assignment)
        }
        assignmentRepository.findScheduleTransitionCandidatesForUpdate(
            CourseStatus.ACTIVE,
            AssignmentLifecycleStatus.ACTIVE,
            AssignmentScheduleStatus.SCHEDULED,
            AssignmentScheduleStatus.OPEN,
            AssignmentScheduleStatus.CLOSED,
            now
        ).forEach { assignment ->
            val expected = when {
                now.isBefore(assignment.kickoffDate) -> AssignmentScheduleStatus.SCHEDULED
                !now.isBefore(assignment.deadlineDate) -> AssignmentScheduleStatus.CLOSED
                else -> AssignmentScheduleStatus.OPEN
            }
            val legacyFinalizationNeeded = expected == AssignmentScheduleStatus.CLOSED &&
                assignment.scheduleStatus == AssignmentScheduleStatus.CLOSED &&
                assignment.finalizedAt == null &&
                assignment.finalizationGeneration == 0
            if (assignment.scheduleStatus != expected || legacyFinalizationNeeded) {
                if (expected == AssignmentScheduleStatus.CLOSED && assignment.finalizationGeneration == 0) {
                    assignment.finalizationGeneration = 1
                } else if (expected == AssignmentScheduleStatus.CLOSED && assignment.scheduleStatus != expected) {
                    assignment.finalizationGeneration += 1
                }
                assignment.scheduleStatus = expected
                if (expected == AssignmentScheduleStatus.CLOSED &&
                    assignment.starterDistributionPending &&
                    !assignment.hasStarterCode
                ) {
                    assignment.starterDistributionPending = false
                    assignment.lastError = null
                }
                assignment.updatedAt = now
                assignmentRepository.save(assignment)
                changedCourseIds += assignment.course.id
                if (expected == AssignmentScheduleStatus.CLOSED) {
                    closeAssignmentJcodes(assignment.id, includeInspectors = false)
                    workspaceOperationStore.enqueue(
                        WorkspaceOperationTarget.ASSIGNMENT,
                        assignment.id,
                        WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
                    )
                }
            }
        }
        changedCourseIds.forEach(workspacePolicyRevisionService::bump)
    }
}
