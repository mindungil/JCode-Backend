package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.StarterArtifactRepository
import org.jbnu.jdevops.jcodeportallogin.repo.WorkspaceOperationRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserCoursesRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.min

data class ClaimedWorkspaceOperation(
    val id: Long,
    val targetType: WorkspaceOperationTarget,
    val targetId: Long,
    val action: WorkspaceOperationAction,
    val artifactId: Long?,
    val desiredRevision: Long?,
    val idempotencyKey: String,
    val attempts: Int
)

@Service
class WorkspaceOperationStore(
    private val operationRepository: WorkspaceOperationRepository,
    private val assignmentRepository: AssignmentRepository,
    private val artifactRepository: StarterArtifactRepository,
    private val userCoursesRepository: UserCoursesRepository,
    private val jCodeRepository: JCodeRepository,
    private val workspacePolicyRevisionService: WorkspacePolicyRevisionService,
    @Value("\${workspace.lifecycle.max-attempts:10}") private val maxAttempts: Int,
    @Value("\${workspace.lifecycle.lock-timeout-seconds:300}") private val lockTimeoutSeconds: Long
) {
    private val active = setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)

    @Transactional(readOnly = true)
    fun assignmentMetadataReady(assignmentId: Long, membershipId: Long): Boolean {
        if (operationRepository.existsByTargetTypeAndTargetIdAndStatusIn(
                WorkspaceOperationTarget.MEMBERSHIP, membershipId, active
            )) return false
        val latest = operationRepository.findTopByTargetTypeAndTargetIdAndActionOrderByIdDesc(
            WorkspaceOperationTarget.ASSIGNMENT, assignmentId,
            WorkspaceOperationAction.UPDATE_ASSIGNMENT_METADATA
        ) ?: return true
        return latest.status == WorkspaceOperationStatus.SUCCEEDED
    }

    @Transactional
    fun enqueue(
        targetType: WorkspaceOperationTarget,
        targetId: Long,
        action: WorkspaceOperationAction,
        artifactId: Long? = null,
        desiredRevision: Long? = null
    ) {
        operationRepository.save(
            WorkspaceOperation(
                targetType = targetType,
                targetId = targetId,
                action = action,
                artifactId = artifactId,
                desiredRevision = desiredRevision
            )
        )
    }

    @Transactional
    fun enqueueOnce(
        targetType: WorkspaceOperationTarget,
        targetId: Long,
        action: WorkspaceOperationAction,
        desiredRevision: Long? = null
    ): Boolean {
        if (operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusIn(
                targetType,
                targetId,
                action,
                active
            )) {
            return false
        }
        operationRepository.save(
            WorkspaceOperation(
                targetType = targetType,
                targetId = targetId,
                action = action,
                desiredRevision = desiredRevision
            )
        )
        return true
    }

    @Transactional
    fun enqueueBackfillOnce(targetId: Long, action: WorkspaceOperationAction) {
        val key = UUID.nameUUIDFromBytes(
            "assignment-v2.0.1:$targetId:${action.name}".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        if (operationRepository.existsByIdempotencyKey(key)) return
        operationRepository.save(
            WorkspaceOperation(
                targetType = WorkspaceOperationTarget.ASSIGNMENT,
                targetId = targetId,
                action = action,
                idempotencyKey = key
            )
        )
    }

    @Transactional
    fun enqueueJcodeAccessReconcile(jcodeId: Long, desiredRevision: Long): Boolean {
        jCodeRepository.findByIdForUpdate(jcodeId).orElse(null) ?: return false
        if (operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusIn(
                WorkspaceOperationTarget.JCODE,
                jcodeId,
                WorkspaceOperationAction.RECONCILE_JCODE_ACCESS,
                active
            )) {
            return false
        }
        val key = UUID.nameUUIDFromBytes(
            "jcode-access:$jcodeId:$desiredRevision".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val operationKey = if (operationRepository.existsByIdempotencyKey(key)) {
            UUID.randomUUID().toString()
        } else {
            key
        }
        operationRepository.save(
            WorkspaceOperation(
                targetType = WorkspaceOperationTarget.JCODE,
                targetId = jcodeId,
                action = WorkspaceOperationAction.RECONCILE_JCODE_ACCESS,
                desiredRevision = desiredRevision,
                idempotencyKey = operationKey
            )
        )
        return true
    }

    // Competing workers must see the committed claim, not an earlier RR snapshot.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun claimNext(): ClaimedWorkspaceOperation? {
        val now = LocalDateTime.now()
        val operation = operationRepository.findReadyForUpdate(
            now,
            now.minusSeconds(lockTimeoutSeconds),
            WorkspaceOperationStatus.PENDING,
            WorkspaceOperationStatus.PROCESSING,
            PageRequest.of(0, 1)
        ).firstOrNull() ?: return null
        operation.status = WorkspaceOperationStatus.PROCESSING
        operation.lockedAt = now
        operation.attempts += 1
        operation.updatedAt = now
        operationRepository.save(operation)
        return ClaimedWorkspaceOperation(
            operation.id, operation.targetType, operation.targetId, operation.action,
            operation.artifactId, operation.desiredRevision, operation.idempotencyKey, operation.attempts
        )
    }

    @Transactional(readOnly = true)
    fun loadAssignment(id: Long) = assignmentRepository.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun loadArtifact(id: Long) = artifactRepository.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun loadLatestAssignmentArtifact(assignmentId: Long) =
        artifactRepository.findTopByAssignmentIdAndStatusAndPublishedAtIsNotNullOrderByVersionDesc(
            assignmentId,
            StarterArtifactStatus.READY
        )

    @Transactional(readOnly = true)
    fun loadMembership(id: Long) = userCoursesRepository.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun loadJcode(id: Long) = jCodeRepository.findById(id).orElse(null)

    @Transactional
    fun cancelJcodeProvision(id: Long): Boolean {
        val jcode = jCodeRepository.findByIdForUpdate(id).orElse(null) ?: return false
        if (jcode.lifecycleStatus != JcodeLifecycleStatus.PROVISIONING) return false
        jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
        jcode.lastError = null
        jCodeRepository.save(jcode)
        return true
    }

    @Transactional
    fun cancelMembershipProvision(id: Long): Boolean {
        val membership = userCoursesRepository.findByIdForUpdate(id) ?: return false
        if (membership.lifecycleStatus != MembershipStatus.PROVISIONING) return false
        membership.lifecycleStatus = MembershipStatus.DELETE_PENDING
        membership.lastError = null
        userCoursesRepository.save(membership)
        enqueueOnce(
            WorkspaceOperationTarget.MEMBERSHIP,
            membership.id,
            WorkspaceOperationAction.DELETE_MEMBERSHIP
        )
        return true
    }

    @Transactional
    fun cancelAssignmentProvision(id: Long): Boolean {
        val current = assignmentRepository.findById(id).orElse(null) ?: return false
        val assignment = assignmentRepository.findByIdAndCourseIdForUpdate(id, current.course.id)
            .orElse(null) ?: return false
        if (assignment.lifecycleStatus != AssignmentLifecycleStatus.PROVISIONING) return false
        assignment.lifecycleStatus = AssignmentLifecycleStatus.DELETING
        assignment.scheduleStatus = AssignmentScheduleStatus.CLOSED
        assignment.starterDistributionPending = false
        assignment.lastError = null
        assignment.updatedAt = LocalDateTime.now()
        assignmentRepository.save(assignment)
        enqueueOnce(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
        )
        return true
    }

    @Transactional
    fun cancelExpiredAssignmentRestore(id: Long): Boolean {
        val current = assignmentRepository.findById(id).orElse(null) ?: return false
        val assignment = assignmentRepository.findByIdAndCourseIdForUpdate(id, current.course.id)
            .orElse(null) ?: return false
        if (assignment.lifecycleStatus != AssignmentLifecycleStatus.PROVISIONING ||
            assignment.finalizedAt == null ||
            LocalDateTime.now().isBefore(assignment.deadlineDate)
        ) return false
        assignment.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
        assignment.scheduleStatus = AssignmentScheduleStatus.CLOSED
        assignment.lastError = null
        assignment.updatedAt = LocalDateTime.now()
        assignmentRepository.save(assignment)
        return true
    }

    @Transactional(readOnly = true)
    fun loadLatestCourseArtifacts(courseId: Long): List<StarterArtifact> =
        artifactRepository.findByAssignmentCourseIdAndStatusAndPublishedAtIsNotNullOrderByVersionDesc(
            courseId,
            StarterArtifactStatus.READY
        )
            .filter {
                it.assignment.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                    it.assignment.scheduleStatus in setOf(
                        AssignmentScheduleStatus.SCHEDULED,
                        AssignmentScheduleStatus.OPEN
                    ) &&
                    LocalDateTime.now().isBefore(it.assignment.deadlineDate)
            }
            .distinctBy { it.assignment.id }

    @Transactional
    fun beginStarterDistribution(artifactId: Long): Boolean {
        val artifact = artifactRepository.findById(artifactId).orElse(null) ?: return false
        val assignment = assignmentRepository.findByIdAndCourseIdForUpdate(
            artifact.assignment.id,
            artifact.assignment.course.id
        ).orElse(null) ?: return false
        if (assignment.lifecycleStatus != AssignmentLifecycleStatus.ACTIVE ||
            assignment.finalizedAt != null ||
            !assignment.starterDistributionPending
        ) {
            return false
        }
        if (artifact.distributionStartedAt != null) return true
        val now = LocalDateTime.now()
        val latestArtifact = artifactRepository.findTopByAssignmentIdOrderByVersionDesc(assignment.id)
        if (latestArtifact?.id != artifact.id) {
            artifact.status = StarterArtifactStatus.ARCHIVED
            artifact.lastError = "STARTER_DISTRIBUTION_SUPERSEDED"
            artifactRepository.save(artifact)
            return false
        }
        if (assignment.course.status != CourseStatus.ACTIVE ||
            assignment.scheduleStatus in setOf(
                AssignmentScheduleStatus.CLOSED,
                AssignmentScheduleStatus.ARCHIVED
            ) ||
            !now.isBefore(assignment.deadlineDate)
        ) {
            artifact.status = StarterArtifactStatus.ARCHIVED
            artifact.lastError = "STARTER_DISTRIBUTION_EXPIRED"
            artifactRepository.save(artifact)
            assignment.starterDistributionPending = false
            assignment.hasStarterCode = artifactRepository
                .findTopByAssignmentIdAndStatusAndPublishedAtIsNotNullOrderByVersionDesc(
                    assignment.id,
                    StarterArtifactStatus.READY
                ) != null
            // The assignment or course moved past the eligible window before any
            // workspace mutation began. This is a normal cancellation, not a fault
            // that should surface as a retryable environment error.
            assignment.lastError = null
            assignment.updatedAt = now
            assignmentRepository.save(assignment)
            return false
        }
        artifact.distributionStartedAt = now
        artifactRepository.save(artifact)
        return true
    }

    private fun enqueueMissingStudentWorkspaceCatchUp(
        course: Course,
        result: Map<*, *>?,
        sourceOperationKey: String
    ): Boolean {
        val processed = (result?.get("processed_students") as? Collection<*>)
            ?.mapNotNull { it as? String }
            ?.toSet()
            .orEmpty()
        val missing = userCoursesRepository.findByCourseId(course.id)
            .filter {
                it.role == RoleType.STUDENT &&
                    it.user.studentNum != null &&
                    it.lifecycleStatus in setOf(MembershipStatus.PROVISIONING, MembershipStatus.READY)
            }
            .filter {
                "${course.infrastructureKey.lowercase()}-${course.clss}-${it.user.studentNum}" !in processed
            }
        missing.forEach {
            // The active membership operation may have built its payload before this
            // assignment became visible. Queue one source-bound follow-up behind it
            // instead of assuming the in-flight request contains the new state.
            val catchUpKey = UUID.nameUUIDFromBytes(
                "membership-catch-up:${it.id}:$sourceOperationKey".toByteArray(StandardCharsets.UTF_8)
            ).toString()
            if (operationRepository.existsByIdempotencyKey(catchUpKey)) return@forEach
            operationRepository.save(
                WorkspaceOperation(
                    targetType = WorkspaceOperationTarget.MEMBERSHIP,
                    targetId = it.id,
                    action = WorkspaceOperationAction.PROVISION_MEMBERSHIP,
                    idempotencyKey = catchUpKey
                )
            )
        }
        return missing.isNotEmpty()
    }

    @Transactional(readOnly = true)
    fun loadMembershipJcodes(membershipId: Long): List<Pair<String, String>> {
        val membership = userCoursesRepository.findById(membershipId).orElse(null) ?: return emptyList()
        return jCodeRepository.findAllByUserCourse(membership).map { it.deploymentName to it.serviceName }
    }

    @Transactional(readOnly = true)
    fun loadAssignmentJcodes(
        assignmentId: Long,
        includeInspectors: Boolean = true
    ): List<Pair<String, String>> =
        jCodeRepository.findByAssignmentId(assignmentId)
            .filter {
                it.lifecycleStatus != JcodeLifecycleStatus.ARCHIVED &&
                    (includeInspectors || it.kind != JcodeKind.INSPECTOR)
            }
            .map { it.deploymentName to it.serviceName }

    @Transactional(readOnly = true)
    fun assignmentInspectorsClosed(assignmentId: Long): Boolean =
        jCodeRepository.findByAssignmentId(assignmentId)
            .filter { it.kind == JcodeKind.INSPECTOR }
            .all { it.lifecycleStatus == JcodeLifecycleStatus.ARCHIVED }

    @Transactional(readOnly = true)
    fun hasFailedAssignmentInspectorCleanup(assignmentId: Long): Boolean =
        jCodeRepository.findByAssignmentId(assignmentId)
            .filter { it.kind == JcodeKind.INSPECTOR }
            .any { it.lifecycleStatus == JcodeLifecycleStatus.DELETE_FAILED }

    @Transactional(readOnly = true)
    fun activeCourseJcodesReconciled(courseId: Long, assignmentId: Long, revision: Long): Boolean {
        return jCodeRepository.findByCourseId(courseId)
            .filter {
                !it.snapshot &&
                    it.kind != JcodeKind.INSPECTOR &&
                    (it.assignment == null || it.assignment?.id == assignmentId)
            }
            .all {
                it.lifecycleStatus == JcodeLifecycleStatus.ARCHIVED ||
                    (
                        it.lifecycleStatus == JcodeLifecycleStatus.READY &&
                            it.observedRevision == revision &&
                            !it.observedMountHash.isNullOrBlank() &&
                            it.observedMountHash == it.desiredMountHash
                    )
            }
    }

    @Transactional(readOnly = true)
    fun hasFailedCourseJcodeReconciliation(courseId: Long, assignmentId: Long): Boolean =
        jCodeRepository.findByCourseId(courseId)
            .filter {
                !it.snapshot &&
                    it.kind != JcodeKind.INSPECTOR &&
                    (it.assignment == null || it.assignment?.id == assignmentId)
            }
            .any {
                it.lifecycleStatus in setOf(
                    JcodeLifecycleStatus.PROVISION_FAILED,
                    JcodeLifecycleStatus.DELETE_FAILED
                ) || it.observedStatus == JcodeObservedStatus.FAILED
            }

    @Transactional(readOnly = true)
    fun courseMembershipMutationsSettled(courseId: Long): Boolean =
        userCoursesRepository.findByCourseId(courseId).none {
            it.lifecycleStatus in setOf(
                MembershipStatus.PROVISIONING,
                MembershipStatus.DELETE_PENDING
            )
        } && !operationRepository.existsActiveMembershipOperationForCourse(
            courseId,
            WorkspaceOperationTarget.MEMBERSHIP,
            active
        )

    @Transactional(readOnly = true)
    fun courseTerminationSettled(courseId: Long): Boolean {
        val assignmentsSettled = assignmentRepository.findByCourseId(courseId).all { assignment ->
            when (assignment.lifecycleStatus) {
                AssignmentLifecycleStatus.ARCHIVED -> true
                AssignmentLifecycleStatus.ACTIVE ->
                    assignment.scheduleStatus == AssignmentScheduleStatus.CLOSED &&
                        assignment.finalizedAt != null &&
                        !assignment.starterDistributionPending
                else -> false
            }
        }
        val jcodesSettled = jCodeRepository.findByCourseId(courseId).all {
            it.lifecycleStatus == JcodeLifecycleStatus.ARCHIVED
        }
        return assignmentsSettled && jcodesSettled && courseMembershipMutationsSettled(courseId)
    }

    @Transactional(readOnly = true)
    fun loadActiveAssignmentKeys(courseId: Long): List<String> = assignmentRepository.findByCourseId(courseId)
        .filter {
            it.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                it.scheduleStatus in setOf(AssignmentScheduleStatus.SCHEDULED, AssignmentScheduleStatus.OPEN) &&
                LocalDateTime.now().isBefore(it.deadlineDate)
        }
        .map { it.workspaceKey }

    @Transactional(readOnly = true)
    fun loadActiveAssignmentLabels(courseId: Long): Map<String, String> = assignmentRepository.findByCourseId(courseId)
        .filter {
            it.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                it.scheduleStatus in setOf(AssignmentScheduleStatus.SCHEDULED, AssignmentScheduleStatus.OPEN) &&
                LocalDateTime.now().isBefore(it.deadlineDate)
        }
        .associate { it.workspaceKey to it.name }

    @Transactional
    fun markSucceeded(operation: ClaimedWorkspaceOperation, result: Map<*, *>? = null) {
        val stored = operationRepository.findById(operation.id).orElseThrow()
        if (stored.status != WorkspaceOperationStatus.PROCESSING || stored.attempts != operation.attempts) return
        if (result?.get("_superseded") == true) {
            stored.status = WorkspaceOperationStatus.SUPERSEDED
            stored.lockedAt = null
            stored.lastError = null
            stored.updatedAt = LocalDateTime.now()
            operationRepository.save(stored)
            return
        }
        if (result?.get("_skipStateUpdate") != true) when (operation.action) {
            WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH,
            WorkspaceOperationAction.PROVISION_ASSIGNMENT -> assignmentRepository.findById(operation.targetId).ifPresent {
                if (it.lifecycleStatus == AssignmentLifecycleStatus.PROVISIONING) {
                    if (it.course.status == CourseStatus.ACTIVE) {
                        it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
                        if (!it.starterDistributionPending || it.hasStarterCode) {
                            it.lastError = null
                        }
                        it.updatedAt = LocalDateTime.now()
                        assignmentRepository.save(it)
                        workspacePolicyRevisionService.bump(it.course.id)
                        enqueueMissingStudentWorkspaceCatchUp(it.course, result, stored.idempotencyKey)
                        if (it.scheduleStatus == AssignmentScheduleStatus.CLOSED && it.finalizedAt == null) {
                            enqueueOnce(
                                WorkspaceOperationTarget.ASSIGNMENT,
                                it.id,
                                WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
                            )
                        }
                    } else {
                        // A course termination can commit while a previously accepted
                        // provisioning call is outside the DB transaction in Generator.
                        it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
                        it.scheduleStatus = AssignmentScheduleStatus.CLOSED
                        it.starterDistributionPending = false
                        it.lastError = null
                        it.updatedAt = LocalDateTime.now()
                        assignmentRepository.save(it)
                        enqueueOnce(
                            WorkspaceOperationTarget.ASSIGNMENT,
                            it.id,
                            WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
                        )
                    }
                }
            }
            WorkspaceOperationAction.UPDATE_ASSIGNMENT_METADATA -> assignmentRepository.findById(operation.targetId).ifPresent {
                if (it.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE && it.course.status == CourseStatus.ACTIVE) {
                    it.lastError = null
                    it.updatedAt = LocalDateTime.now()
                    assignmentRepository.save(it)
                    enqueueMissingStudentWorkspaceCatchUp(it.course, result, stored.idempotencyKey)
                }
            }
            WorkspaceOperationAction.RESTORE_ASSIGNMENT -> assignmentRepository.findById(operation.targetId).ifPresent {
                if (it.lifecycleStatus == AssignmentLifecycleStatus.PROVISIONING &&
                    it.course.status == CourseStatus.ACTIVE
                ) {
                    it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
                    it.finalizedAt = null
                    it.lastError = null
                    it.updatedAt = LocalDateTime.now()
                    assignmentRepository.save(it)
                    workspacePolicyRevisionService.bump(it.course.id)
                    enqueueMissingStudentWorkspaceCatchUp(it.course, result, stored.idempotencyKey)
                } else if (it.lifecycleStatus == AssignmentLifecycleStatus.PROVISIONING) {
                    // A restore accepted by an active course may finish after the course has
                    // started terminating. Never leave that assignment stuck in PROVISIONING.
                    it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
                    it.scheduleStatus = AssignmentScheduleStatus.CLOSED
                    it.starterDistributionPending = false
                    it.lastError = null
                    it.updatedAt = LocalDateTime.now()
                    assignmentRepository.save(it)
                    enqueueOnce(
                        WorkspaceOperationTarget.ASSIGNMENT,
                        it.id,
                        WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
                    )
                }
            }
            WorkspaceOperationAction.DISTRIBUTE_STARTER -> {
                val artifact = operation.artifactId
                    ?.let { artifactRepository.findById(it).orElse(null) }
                    ?: throw IllegalStateException("스타터 artifact를 찾을 수 없습니다.")
                val assignment = artifact.assignment
                artifact.status = StarterArtifactStatus.READY
                val firstCompletedPass = artifact.publishedAt == null
                if (firstCompletedPass) artifact.publishedAt = LocalDateTime.now()
                artifact.lastError = null
                artifactRepository.save(artifact)
                val distributionStillCurrent =
                    assignment.course.status in setOf(CourseStatus.ACTIVE, CourseStatus.TERMINATING) &&
                        assignment.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                        assignment.starterDistributionPending &&
                        assignment.finalizedAt == null
                if (distributionStillCurrent) {
                    assignment.hasStarterCode = true
                    assignment.lastError = null
                    assignmentRepository.save(assignment)
                    val missingStudents = enqueueMissingStudentWorkspaceCatchUp(
                        assignment.course,
                        result,
                        stored.idempotencyKey
                    )
                    if (firstCompletedPass || missingStudents) {
                        stored.status = WorkspaceOperationStatus.PENDING
                        stored.attempts = (stored.attempts - 1).coerceAtLeast(0)
                        stored.nextAttemptAt = LocalDateTime.now().plusSeconds(3)
                        stored.lockedAt = null
                        stored.lastError = null
                        stored.updatedAt = LocalDateTime.now()
                        operationRepository.save(stored)
                        return
                    }
                    assignment.starterDistributionPending = false
                    assignmentRepository.save(assignment)
                    workspacePolicyRevisionService.bump(assignment.course.id)
                }
            }
            WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION -> {
                val assignment = assignmentRepository.findById(operation.targetId)
                    .orElseThrow { IllegalStateException("최종 보관 대상 과제를 찾을 수 없습니다.") }
                if (assignment.finalizedAt == null) {
                    if (assignment.lifecycleStatus == AssignmentLifecycleStatus.DELETING ||
                        assignment.lifecycleStatus == AssignmentLifecycleStatus.ARCHIVED
                    ) {
                        // A newer explicit archive owns the final state.
                    } else if (assignment.lifecycleStatus != AssignmentLifecycleStatus.ACTIVE ||
                        assignment.scheduleStatus != AssignmentScheduleStatus.CLOSED) {
                        throw IllegalStateException("닫힌 ACTIVE 과제만 최종 보관 완료 처리할 수 있습니다.")
                    } else {
                        assignment.finalizedAt = LocalDateTime.now()
                        assignment.lastError = null
                        assignmentRepository.save(assignment)
                    }
                }
            }
            WorkspaceOperationAction.ARCHIVE_ASSIGNMENT -> assignmentRepository.findById(operation.targetId).ifPresent {
                it.lifecycleStatus = AssignmentLifecycleStatus.ARCHIVED
                it.scheduleStatus = AssignmentScheduleStatus.ARCHIVED
                it.starterDistributionPending = false
                it.archivedAt = LocalDateTime.now()
                it.lastError = null
                assignmentRepository.save(it)
            }
            WorkspaceOperationAction.PROVISION_MEMBERSHIP -> userCoursesRepository.findById(operation.targetId).ifPresent {
                if (it.lifecycleStatus == MembershipStatus.PROVISIONING) {
                    if (it.course.status == CourseStatus.ACTIVE) {
                        it.lifecycleStatus = MembershipStatus.READY
                        it.lastError = null
                        userCoursesRepository.save(it)
                    } else {
                        it.lifecycleStatus = MembershipStatus.DELETE_PENDING
                        it.lastError = null
                        userCoursesRepository.save(it)
                        enqueueOnce(
                            WorkspaceOperationTarget.MEMBERSHIP,
                            it.id,
                            WorkspaceOperationAction.DELETE_MEMBERSHIP
                        )
                    }
                }
            }
            WorkspaceOperationAction.DELETE_MEMBERSHIP -> userCoursesRepository.findById(operation.targetId).ifPresent {
                it.lifecycleStatus = MembershipStatus.ARCHIVED
                it.archivedAt = LocalDateTime.now()
                it.lastError = null
                it.jcodes.forEach { jcode ->
                    jcode.lifecycleStatus = JcodeLifecycleStatus.ARCHIVED
                    jcode.archivedAt = LocalDateTime.now()
                    jCodeRepository.save(jcode)
                }
                userCoursesRepository.save(it)
            }
            WorkspaceOperationAction.PROVISION_JCODE -> jCodeRepository.findById(operation.targetId).ifPresent {
                if (it.lifecycleStatus == JcodeLifecycleStatus.PROVISIONING && it.userCourse.lifecycleStatus == MembershipStatus.READY) {
                    it.jcodeUrl = result?.get("jcodeUrl") as? String
                        ?: throw IllegalStateException("Generator가 jcodeUrl을 반환하지 않았습니다.")
                    it.lifecycleStatus = JcodeLifecycleStatus.READY
                    it.observedStatus = JcodeObservedStatus.READY
                    it.observedReason = "READY"
                    it.lastObservedAt = LocalDateTime.now()
                    it.observedRevision = (result?.get("policyRevision") as? Number)?.toLong() ?: it.desiredRevision
                    it.desiredMountHash = result?.get("mountHash") as? String
                    it.observedMountHash = result?.get("mountHash") as? String
                    it.lastError = null
                    jCodeRepository.save(it)
                } else {
                    if (it.lifecycleStatus != JcodeLifecycleStatus.ARCHIVED) {
                        it.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
                        jCodeRepository.save(it)
                    }
                    operationRepository.save(
                        WorkspaceOperation(
                            targetType = WorkspaceOperationTarget.JCODE,
                            targetId = it.id,
                            action = WorkspaceOperationAction.DELETE_JCODE
                        )
                    )
                }
            }
            WorkspaceOperationAction.RECONCILE_JCODE_ACCESS -> jCodeRepository.findById(operation.targetId).ifPresent {
                val appliedRevision = (result?.get("policyRevision") as? Number)?.toLong()
                    ?: throw IllegalStateException("Generator가 policyRevision을 반환하지 않았습니다.")
                val mountHash = result["mountHash"] as? String
                    ?: throw IllegalStateException("Generator가 mountHash를 반환하지 않았습니다.")
                if (appliedRevision == it.desiredRevision) {
                    it.desiredMountHash = mountHash
                    it.observedRevision = appliedRevision
                    it.observedMountHash = mountHash
                    it.observedStatus = JcodeObservedStatus.READY
                    it.observedReason = "READY"
                    it.lastObservedAt = LocalDateTime.now()
                    it.lastError = null
                    jCodeRepository.save(it)
                }
            }
            WorkspaceOperationAction.DELETE_JCODE -> jCodeRepository.findById(operation.targetId).ifPresent {
                it.lifecycleStatus = JcodeLifecycleStatus.ARCHIVED
                it.observedStatus = JcodeObservedStatus.MISSING
                it.observedReason = "DELETED"
                it.lastObservedAt = LocalDateTime.now()
                it.archivedAt = LocalDateTime.now()
                it.lastError = null
                jCodeRepository.save(it)
            }
        }
        stored.status = WorkspaceOperationStatus.SUCCEEDED
        stored.lockedAt = null
        stored.lastError = null
        stored.updatedAt = LocalDateTime.now()
        operationRepository.save(stored)
    }

    @Transactional
    fun markFailed(operation: ClaimedWorkspaceOperation, error: Throwable) {
        val stored = operationRepository.findById(operation.id).orElseThrow()
        if (stored.status != WorkspaceOperationStatus.PROCESSING || stored.attempts != operation.attempts) return
        val now = LocalDateTime.now()
        val message = (error.message ?: error.javaClass.simpleName).take(4000)
        stored.lastError = message
        stored.lockedAt = null
        stored.updatedAt = now
        if (stored.attempts >= maxAttempts) {
            stored.status = WorkspaceOperationStatus.FAILED
            when (operation.targetType) {
                WorkspaceOperationTarget.ASSIGNMENT -> assignmentRepository.findById(operation.targetId).ifPresent { assignment ->
                    val changed = when (operation.action) {
                        WorkspaceOperationAction.PROVISION_ASSIGNMENT,
                        WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH,
                        WorkspaceOperationAction.RESTORE_ASSIGNMENT -> {
                            if (assignment.lifecycleStatus != AssignmentLifecycleStatus.PROVISIONING) {
                                false
                            } else if (assignment.course.status == CourseStatus.ACTIVE) {
                                assignment.lifecycleStatus = AssignmentLifecycleStatus.PROVISION_FAILED
                                assignment.lastError = message
                                true
                            } else {
                                assignment.lifecycleStatus = AssignmentLifecycleStatus.DELETING
                                assignment.scheduleStatus = AssignmentScheduleStatus.CLOSED
                                assignment.starterDistributionPending = false
                                assignment.lastError = null
                                enqueueOnce(
                                    WorkspaceOperationTarget.ASSIGNMENT,
                                    assignment.id,
                                    WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
                                )
                                true
                            }
                        }
                        WorkspaceOperationAction.UPDATE_ASSIGNMENT_METADATA ->
                            (assignment.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                                assignment.course.status == CourseStatus.ACTIVE).also {
                                if (it) assignment.lastError = message
                            }
                        WorkspaceOperationAction.DISTRIBUTE_STARTER ->
                            (assignment.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                                assignment.starterDistributionPending &&
                                assignment.finalizedAt == null).also {
                                if (it) assignment.lastError = message
                            }
                        WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION ->
                            (assignment.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                                assignment.scheduleStatus == AssignmentScheduleStatus.CLOSED &&
                                assignment.finalizedAt == null).also {
                                if (it) assignment.lastError = message
                            }
                        WorkspaceOperationAction.ARCHIVE_ASSIGNMENT ->
                            (assignment.lifecycleStatus == AssignmentLifecycleStatus.DELETING).also {
                                if (it) assignment.lastError = message
                            }
                        else -> false
                    }
                    if (changed) assignmentRepository.save(assignment)
                }
                WorkspaceOperationTarget.MEMBERSHIP -> userCoursesRepository.findById(operation.targetId).ifPresent { membership ->
                    val changed = when (operation.action) {
                        WorkspaceOperationAction.PROVISION_MEMBERSHIP -> {
                            if (membership.lifecycleStatus != MembershipStatus.PROVISIONING) {
                                false
                            } else if (membership.course.status == CourseStatus.ACTIVE) {
                                membership.lifecycleStatus = MembershipStatus.PROVISION_FAILED
                                membership.lastError = message
                                true
                            } else {
                                membership.lifecycleStatus = MembershipStatus.DELETE_PENDING
                                membership.lastError = null
                                enqueueOnce(
                                    WorkspaceOperationTarget.MEMBERSHIP,
                                    membership.id,
                                    WorkspaceOperationAction.DELETE_MEMBERSHIP
                                )
                                true
                            }
                        }
                        WorkspaceOperationAction.DELETE_MEMBERSHIP ->
                            (membership.lifecycleStatus in setOf(
                                MembershipStatus.DELETE_PENDING,
                                MembershipStatus.DELETE_FAILED
                            )).also {
                                if (it) {
                                    membership.lifecycleStatus = MembershipStatus.DELETE_FAILED
                                    membership.lastError = message
                                }
                            }
                        else -> false
                    }
                    if (changed) userCoursesRepository.save(membership)
                }
                WorkspaceOperationTarget.JCODE -> jCodeRepository.findById(operation.targetId).ifPresent { jcode ->
                    val changed = when (operation.action) {
                        WorkspaceOperationAction.PROVISION_JCODE -> {
                            if (jcode.lifecycleStatus != JcodeLifecycleStatus.PROVISIONING) {
                                false
                            } else if (
                                jcode.course.status == CourseStatus.ACTIVE &&
                                jcode.course.workspaceRuntimeEnabled &&
                                jcode.userCourse.lifecycleStatus == MembershipStatus.READY &&
                                (operation.desiredRevision == null ||
                                    operation.desiredRevision == jcode.desiredRevision)
                            ) {
                                jcode.lifecycleStatus = JcodeLifecycleStatus.PROVISION_FAILED
                                jcode.lastError = message
                                true
                            } else {
                                jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
                                jcode.lastError = null
                                enqueueOnce(
                                    WorkspaceOperationTarget.JCODE,
                                    jcode.id,
                                    WorkspaceOperationAction.DELETE_JCODE
                                )
                                true
                            }
                        }
                        WorkspaceOperationAction.RECONCILE_JCODE_ACCESS -> {
                            if (jcode.lifecycleStatus == JcodeLifecycleStatus.READY &&
                                operation.desiredRevision == jcode.desiredRevision
                            ) {
                                // A permanently drifted workload must not block assignment
                                // finalization forever. Delete it and let the next launch create
                                // a fresh instance from the current access policy.
                                jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
                                jcode.lastError = null
                                enqueueOnce(
                                    WorkspaceOperationTarget.JCODE,
                                    jcode.id,
                                    WorkspaceOperationAction.DELETE_JCODE
                                )
                                true
                            } else {
                                false
                            }
                        }
                        WorkspaceOperationAction.DELETE_JCODE ->
                            (jcode.lifecycleStatus in setOf(
                                JcodeLifecycleStatus.DELETE_PENDING,
                                JcodeLifecycleStatus.DELETE_FAILED
                            )).also {
                                if (it) {
                                    jcode.lifecycleStatus = JcodeLifecycleStatus.DELETE_FAILED
                                    jcode.lastError = message
                                }
                            }
                        else -> false
                    }
                    if (changed) {
                        jcode.observedStatus = JcodeObservedStatus.FAILED
                        jcode.observedReason = "RECONCILE_FAILED"
                        jcode.lastObservedAt = now
                        jCodeRepository.save(jcode)
                    }
                }
            }
            operation.artifactId?.let { id -> artifactRepository.findById(id).ifPresent { artifact ->
                artifact.status = StarterArtifactStatus.FAILED
                artifact.lastError = message
                artifactRepository.save(artifact)
            } }
        } else {
            stored.status = WorkspaceOperationStatus.PENDING
            stored.nextAttemptAt = now.plusSeconds(min(300L, 1L shl min(stored.attempts, 8)))
        }
        operationRepository.save(stored)
    }

    @Transactional
    fun defer(operation: ClaimedWorkspaceOperation, delaySeconds: Long = 3) {
        val stored = operationRepository.findById(operation.id).orElseThrow()
        if (stored.status != WorkspaceOperationStatus.PROCESSING || stored.attempts != operation.attempts) return
        val now = LocalDateTime.now()
        stored.status = WorkspaceOperationStatus.PENDING
        // Readiness polling is not a failed attempt and must not exhaust max-attempts.
        stored.attempts = (stored.attempts - 1).coerceAtLeast(0)
        stored.nextAttemptAt = now.plusSeconds(delaySeconds)
        stored.lockedAt = null
        stored.lastError = null
        stored.updatedAt = now
        operationRepository.save(stored)
    }

    @Transactional
    fun retry(targetType: WorkspaceOperationTarget, targetId: Long): Boolean {
        val operation = operationRepository.findTopByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            targetType, targetId, WorkspaceOperationStatus.FAILED
        ) ?: return false
        val hasActiveOperation = operationRepository.existsByTargetTypeAndTargetIdAndStatusIn(
            targetType,
            targetId,
            active
        )
        val assignment = if (targetType == WorkspaceOperationTarget.ASSIGNMENT) {
            assignmentRepository.findById(targetId).orElse(null)
        } else null
        val canPreemptLaterAssignmentWork =
            (operation.action == WorkspaceOperationAction.DISTRIBUTE_STARTER &&
                assignment?.starterDistributionPending == true) ||
                (operation.action in setOf(
                    WorkspaceOperationAction.PROVISION_ASSIGNMENT,
                    WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH,
                    WorkspaceOperationAction.RESTORE_ASSIGNMENT
                ) && assignment?.lifecycleStatus == AssignmentLifecycleStatus.PROVISION_FAILED)
        if (hasActiveOperation && !canPreemptLaterAssignmentWork) return false
        if (operation.action == WorkspaceOperationAction.DISTRIBUTE_STARTER) {
            val artifact = operation.artifactId
                ?.let { artifactRepository.findById(it).orElse(null) }
            val latestArtifact = assignment
                ?.let { artifactRepository.findTopByAssignmentIdOrderByVersionDesc(it.id) }
            val superseded = operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusInAndIdGreaterThan(
                targetType,
                targetId,
                operation.action,
                setOf(WorkspaceOperationStatus.SUCCEEDED, WorkspaceOperationStatus.SUPERSEDED),
                operation.id
            )
            val finalized = assignmentRepository.findById(targetId).orElse(null)?.finalizedAt != null
            val staleArtifact = artifact == null ||
                artifact.assignment.id != targetId ||
                latestArtifact?.id != artifact.id
            if (superseded || finalized || staleArtifact) {
                operation.status = WorkspaceOperationStatus.SUPERSEDED
                operation.lockedAt = null
                operation.lastError = null
                operation.updatedAt = LocalDateTime.now()
                operationRepository.save(operation)
                return false
            }
        }
        operation.status = WorkspaceOperationStatus.PENDING
        operation.attempts = 0
        operation.nextAttemptAt = LocalDateTime.now()
        operation.lockedAt = null
        operation.lastError = null
        operationRepository.save(operation)
        if (targetType == WorkspaceOperationTarget.ASSIGNMENT) {
            assignmentRepository.findById(targetId).ifPresent {
                if (operation.action in setOf(
                        WorkspaceOperationAction.PROVISION_ASSIGNMENT,
                        WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH,
                        WorkspaceOperationAction.RESTORE_ASSIGNMENT
                    )) {
                    it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
                }
                it.lastError = null
                assignmentRepository.save(it)
            }
        } else if (targetType == WorkspaceOperationTarget.MEMBERSHIP) {
            userCoursesRepository.findById(targetId).ifPresent {
                it.lifecycleStatus = if (operation.action == WorkspaceOperationAction.DELETE_MEMBERSHIP) {
                    MembershipStatus.DELETE_PENDING
                } else MembershipStatus.PROVISIONING
                it.lastError = null
                userCoursesRepository.save(it)
            }
        } else {
            jCodeRepository.findById(targetId).ifPresent {
                it.lifecycleStatus = when (operation.action) {
                    WorkspaceOperationAction.DELETE_JCODE -> JcodeLifecycleStatus.DELETE_PENDING
                    WorkspaceOperationAction.PROVISION_JCODE -> JcodeLifecycleStatus.PROVISIONING
                    WorkspaceOperationAction.RECONCILE_JCODE_ACCESS -> it.lifecycleStatus
                    else -> throw IllegalStateException("JCode 대상에 잘못된 재시도 작업입니다: ${operation.action}")
                }
                it.lastError = null
                if (operation.action == WorkspaceOperationAction.RECONCILE_JCODE_ACCESS) {
                    it.observedStatus = JcodeObservedStatus.UNKNOWN
                    it.observedReason = "RECONCILE_RETRY_PENDING"
                }
                jCodeRepository.save(it)
            }
        }
        return true
    }
}
