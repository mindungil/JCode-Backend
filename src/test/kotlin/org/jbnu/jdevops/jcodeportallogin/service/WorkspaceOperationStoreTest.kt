package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.Optional

class WorkspaceOperationStoreTest {
    private val operationRepository = mock(WorkspaceOperationRepository::class.java)
    private val assignmentRepository = mock(AssignmentRepository::class.java)
    private val artifactRepository = mock(StarterArtifactRepository::class.java)
    private val membershipRepository = mock(UserCoursesRepository::class.java)
    private val jcodeRepository = mock(JCodeRepository::class.java)
    private val workspacePolicyRevisionService = mock(WorkspacePolicyRevisionService::class.java)
    private val store = WorkspaceOperationStore(
        operationRepository,
        assignmentRepository,
        artifactRepository,
        membershipRepository,
        jcodeRepository,
        workspacePolicyRevisionService,
        3,
        300
    )

    @Test
    fun `completed stale provision does not undo assignment deletion`() {
        val assignment = assignment().also { it.lifecycleStatus = AssignmentLifecycleStatus.DELETING }
        val stored = operation(10, WorkspaceOperationTarget.ASSIGNMENT, assignment.id, WorkspaceOperationAction.PROVISION_ASSIGNMENT)
        val claimed = claimed(stored)
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))

        store.markSucceeded(claimed, mapOf("ok" to true))

        assertEquals(AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        assertEquals(WorkspaceOperationStatus.SUCCEEDED, stored.status)
    }

    @Test
    fun `assignment is exposed only after storage provisioning succeeds`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
        }
        val stored = operation(
            14,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.PROVISION_ASSIGNMENT
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))

        store.markSucceeded(claimed(stored), mapOf("provisioned" to true))

        assertEquals(AssignmentLifecycleStatus.ACTIVE, assignment.lifecycleStatus)
        verify(workspacePolicyRevisionService).bump(assignment.course.id)
    }

    @Test
    fun `late assignment provision is archived instead of reactivating a terminating course`() {
        val terminatingCourse = course().also { it.status = CourseStatus.TERMINATING }
        val assignment = assignment().copy(course = terminatingCourse).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
            it.starterDistributionPending = true
        }
        val stored = operation(
            15,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.PROVISION_ASSIGNMENT
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusIn(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_ASSIGNMENT,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(false)

        store.markSucceeded(claimed(stored), mapOf("provisioned" to true))

        assertEquals(AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        assertEquals(AssignmentScheduleStatus.CLOSED, assignment.scheduleStatus)
        assertFalse(assignment.starterDistributionPending)
        verify(workspacePolicyRevisionService, never()).bump(terminatingCourse.id)
        val captured = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository, times(2)).save(captured.capture())
        assertEquals(WorkspaceOperationAction.ARCHIVE_ASSIGNMENT, captured.allValues.first().action)
        assertEquals(WorkspaceOperationStatus.SUCCEEDED, captured.allValues.last().status)
    }

    @Test
    fun `assignment provision cancellation enters durable archive flow`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
            it.starterDistributionPending = true
        }
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, assignment.course.id))
            .thenReturn(Optional.of(assignment))

        assertTrue(store.cancelAssignmentProvision(assignment.id))

        assertEquals(AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        assertEquals(AssignmentScheduleStatus.CLOSED, assignment.scheduleStatus)
        assertFalse(assignment.starterDistributionPending)
        val captured = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository).save(captured.capture())
        assertEquals(WorkspaceOperationAction.ARCHIVE_ASSIGNMENT, captured.value.action)
    }

    @Test
    fun `expired restore returns to its previously finalized closed state`() {
        val finalizedAt = LocalDateTime.now().minusHours(1)
        val assignment = assignment().copy(
            lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            finalizedAt = finalizedAt,
            deadlineDate = LocalDateTime.now().minusMinutes(1)
        )
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, assignment.course.id))
            .thenReturn(Optional.of(assignment))

        assertTrue(store.cancelExpiredAssignmentRestore(assignment.id))

        assertEquals(AssignmentLifecycleStatus.ACTIVE, assignment.lifecycleStatus)
        assertEquals(AssignmentScheduleStatus.CLOSED, assignment.scheduleStatus)
        assertEquals(finalizedAt, assignment.finalizedAt)
    }

    @Test
    fun `provision retry guarantees finalization when assignment became closed while failed`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.finalizedAt = null
        }
        val stored = operation(
            19,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.PROVISION_ASSIGNMENT
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusIn(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(false)

        store.markSucceeded(claimed(stored), mapOf("provisioned" to true))

        assertEquals(AssignmentLifecycleStatus.ACTIVE, assignment.lifecycleStatus)
        val captured = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository, times(2)).save(captured.capture())
        assertEquals(WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION, captured.allValues.first().action)
        assertEquals(WorkspaceOperationStatus.SUCCEEDED, captured.allValues.last().status)
    }

    @Test
    fun `same revision can enqueue a fresh repair after an earlier operation completed`() {
        val jcode = Jcode(
            id = 4,
            userCourse = UserCourses(
                id = 3,
                course = course(),
                user = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001),
                role = RoleType.STUDENT
            ),
            course = course(),
            user = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        )
        `when`(jcodeRepository.findByIdForUpdate(jcode.id)).thenReturn(Optional.of(jcode))
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusIn(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.RECONCILE_JCODE_ACCESS,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(false)
        `when`(operationRepository.existsByIdempotencyKey(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(true)

        assertTrue(store.enqueueJcodeAccessReconcile(jcode.id, 7))

        val captured = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository).save(captured.capture())
        assertEquals(WorkspaceOperationAction.RECONCILE_JCODE_ACCESS, captured.value.action)
        assertEquals(7, captured.value.desiredRevision)
        assertEquals(36, captured.value.idempotencyKey.length)
    }

    @Test
    fun `in flight jcode provision is compensated after membership deletion starts`() {
        val course = course()
        val user = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 3,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.DELETE_PENDING
        )
        val jcode = Jcode(
            id = 4,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.PROVISIONING
        )
        val stored = operation(11, WorkspaceOperationTarget.JCODE, jcode.id, WorkspaceOperationAction.PROVISION_JCODE)
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(jcodeRepository.findById(jcode.id)).thenReturn(Optional.of(jcode))

        store.markSucceeded(claimed(stored), mapOf("jcodeUrl" to "http://example"))

        assertEquals(JcodeLifecycleStatus.DELETE_PENDING, jcode.lifecycleStatus)
        val operations = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository, times(2)).save(operations.capture())
        assertEquals(WorkspaceOperationAction.DELETE_JCODE, operations.allValues.first().action)
        assertEquals(WorkspaceOperationStatus.SUCCEEDED, operations.allValues.last().status)
    }

    @Test
    fun `cancel provision locks jcode and changes only provisioning lifecycle`() {
        val course = course()
        val user = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 3,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val jcode = Jcode(
            id = 4,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.PROVISIONING,
            lastError = "old failure"
        )
        `when`(jcodeRepository.findByIdForUpdate(jcode.id)).thenReturn(Optional.of(jcode))

        assertTrue(store.cancelJcodeProvision(jcode.id))

        assertEquals(JcodeLifecycleStatus.DELETE_PENDING, jcode.lifecycleStatus)
        assertEquals(null, jcode.lastError)
        verify(jcodeRepository).save(jcode)
    }

    @Test
    fun `backfill operation is not duplicated on rerun`() {
        store.enqueueBackfillOnce(5, WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH)

        val operation = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository).save(operation.capture())
        assertEquals(36, operation.value.idempotencyKey.length)
        assertEquals(WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH, operation.value.action)

        `when`(operationRepository.existsByIdempotencyKey(operation.value.idempotencyKey)).thenReturn(true)
        store.enqueueBackfillOnce(5, WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH)

        verify(operationRepository, times(1)).save(org.mockito.ArgumentMatchers.any(WorkspaceOperation::class.java))
    }

    @Test
    fun `readiness polling is deferred without consuming retry attempts`() {
        val stored = operation(
            12,
            WorkspaceOperationTarget.JCODE,
            4,
            WorkspaceOperationAction.PROVISION_JCODE
        ).also {
            it.status = WorkspaceOperationStatus.PROCESSING
            it.attempts = 1
        }
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))

        store.defer(claimed(stored))

        assertEquals(WorkspaceOperationStatus.PENDING, stored.status)
        assertEquals(0, stored.attempts)
        assertEquals(null, stored.lockedAt)
    }

    @Test
    fun `retrying access reconciliation keeps ready lifecycle and resets observation`() {
        val jcode = Jcode(
            id = 4,
            userCourse = UserCourses(
                id = 3,
                course = course(),
                user = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001),
                role = RoleType.STUDENT,
                lifecycleStatus = MembershipStatus.READY
            ),
            course = course(),
            user = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001),
            lifecycleStatus = JcodeLifecycleStatus.READY,
            observedStatus = JcodeObservedStatus.FAILED
        )
        val failed = operation(
            17,
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.RECONCILE_JCODE_ACCESS
        ).also { it.status = WorkspaceOperationStatus.FAILED }
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndStatusIn(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(false)
        `when`(operationRepository.findTopByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationStatus.FAILED
        )).thenReturn(failed)
        `when`(jcodeRepository.findById(jcode.id)).thenReturn(Optional.of(jcode))

        assertTrue(store.retry(WorkspaceOperationTarget.JCODE, jcode.id))

        assertEquals(JcodeLifecycleStatus.READY, jcode.lifecycleStatus)
        assertEquals(JcodeObservedStatus.UNKNOWN, jcode.observedStatus)
        assertEquals("RECONCILE_RETRY_PENDING", jcode.observedReason)
        assertEquals(WorkspaceOperationStatus.PENDING, failed.status)
    }

    @Test
    fun `final archive completion cannot be recorded while assignment is open`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
        }
        val stored = operation(
            13,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))

        assertThrows<IllegalStateException> {
            store.markSucceeded(claimed(stored), mapOf("finalized" to true))
        }

        assertEquals(WorkspaceOperationStatus.PROCESSING, stored.status)
        assertEquals(null, assignment.finalizedAt)
    }

    @Test
    fun `late restore completion cannot reactivate an assignment being deleted`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.finalizedAt = java.time.LocalDateTime.now()
        }
        val stored = operation(
            16,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.RESTORE_ASSIGNMENT
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))

        store.markSucceeded(claimed(stored), mapOf("restored" to true))

        assertEquals(AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        assertNotNull(assignment.finalizedAt)
        verify(workspacePolicyRevisionService, never()).bump(assignment.course.id)
    }

    @Test
    fun `restore completion after course termination is compensated by assignment archive`() {
        val assignment = assignment().also {
            it.course.status = CourseStatus.TERMINATING
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
            it.finalizedAt = java.time.LocalDateTime.now()
        }
        val stored = operation(
            17,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.RESTORE_ASSIGNMENT
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusIn(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_ASSIGNMENT,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(false)

        store.markSucceeded(claimed(stored), mapOf("restored" to true))

        assertEquals(AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        assertEquals(AssignmentScheduleStatus.CLOSED, assignment.scheduleStatus)
        verify(workspacePolicyRevisionService, never()).bump(assignment.course.id)
        val captured = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository, times(2)).save(captured.capture())
        assertTrue(captured.allValues.any {
            it.targetId == assignment.id && it.action == WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
        })
    }

    @Test
    fun `late finalization completion yields to a newer explicit archive`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
        }
        val stored = operation(
            18,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))

        store.markSucceeded(claimed(stored), mapOf("finalized" to true))

        assertEquals(WorkspaceOperationStatus.SUCCEEDED, stored.status)
        assertEquals(null, assignment.finalizedAt)
    }

    @Test
    fun `membership preparation excludes closed and archived assignment storage`() {
        val course = course()
        val closed = assignment().copy(id = 6, course = course, workspaceKey = "assignment-6").also {
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
        }
        val archived = assignment().copy(id = 7, course = course, workspaceKey = "assignment-7").also {
            it.scheduleStatus = AssignmentScheduleStatus.ARCHIVED
            it.lifecycleStatus = AssignmentLifecycleStatus.ARCHIVED
        }
        `when`(assignmentRepository.findByCourseId(course.id)).thenReturn(listOf(closed, archived))

        assertEquals(emptyList<String>(), store.loadActiveAssignmentKeys(course.id))
        assertEquals(emptyMap<String, String>(), store.loadActiveAssignmentLabels(course.id))
    }

    @Test
    fun `finalization workload list excludes read only inspectors`() {
        val course = course()
        val student = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(id = 3, course = course, user = student, role = RoleType.STUDENT)
        val assignment = assignment().copy(course = course)
        val standard = Jcode(
            id = 4,
            userCourse = membership,
            course = course,
            user = student,
            assignment = assignment,
            kind = JcodeKind.STANDARD,
            deploymentName = "standard",
            serviceName = "standard-svc"
        )
        val inspector = Jcode(
            id = 5,
            userCourse = membership,
            course = course,
            user = student,
            assignment = assignment,
            kind = JcodeKind.INSPECTOR,
            deploymentName = "inspector",
            serviceName = "inspector-svc"
        )
        `when`(jcodeRepository.findByAssignmentId(assignment.id)).thenReturn(listOf(standard, inspector))

        assertEquals(
            listOf("standard" to "standard-svc"),
            store.loadAssignmentJcodes(assignment.id, includeInspectors = false)
        )
        assertEquals(2, store.loadAssignmentJcodes(assignment.id).size)
        assertFalse(store.assignmentInspectorsClosed(assignment.id))
        inspector.lifecycleStatus = JcodeLifecycleStatus.ARCHIVED
        assertTrue(store.assignmentInspectorsClosed(assignment.id))
    }

    @Test
    fun `starter distribution waiting at the deadline is discarded before mutating workspaces`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.starterDistributionPending = true
            it.hasStarterCode = true
        }
        val artifact = StarterArtifact(
            id = 21,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/5/starter/v1.zip",
            overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
        )
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, assignment.course.id))
            .thenReturn(Optional.of(assignment))
        `when`(artifactRepository.findTopByAssignmentIdOrderByVersionDesc(assignment.id)).thenReturn(artifact)
        `when`(artifactRepository.findTopByAssignmentIdAndStatusAndPublishedAtIsNotNullOrderByVersionDesc(
            assignment.id,
            StarterArtifactStatus.READY
        )).thenReturn(null)

        assertFalse(store.beginStarterDistribution(artifact.id))

        assertEquals(StarterArtifactStatus.ARCHIVED, artifact.status)
        assertEquals("STARTER_DISTRIBUTION_EXPIRED", artifact.lastError)
        assertFalse(assignment.starterDistributionPending)
        assertFalse(assignment.hasStarterCode)
        assertEquals(null, artifact.distributionStartedAt)
    }

    @Test
    fun `starter distribution already mutating a batch may finish after schedule closes`() {
        val assignment = assignment().also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.starterDistributionPending = true
        }
        val artifact = StarterArtifact(
            id = 22,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/5/starter/v1.zip",
            overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
        )
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, assignment.course.id))
            .thenReturn(Optional.of(assignment))
        `when`(artifactRepository.findTopByAssignmentIdOrderByVersionDesc(assignment.id)).thenReturn(artifact)

        assertTrue(store.beginStarterDistribution(artifact.id))
        assertNotNull(artifact.distributionStartedAt)
        assignment.scheduleStatus = AssignmentScheduleStatus.CLOSED
        assertTrue(store.beginStarterDistribution(artifact.id))

        assignment.finalizedAt = java.time.LocalDateTime.now()
        assertFalse(store.beginStarterDistribution(artifact.id))
    }

    @Test
    fun `late failure cannot revert an operation that already succeeded`() {
        val stored = operation(
            25,
            WorkspaceOperationTarget.ASSIGNMENT,
            5,
            WorkspaceOperationAction.UPDATE_ASSIGNMENT_METADATA
        ).also {
            it.status = WorkspaceOperationStatus.SUCCEEDED
            it.attempts = 1
        }
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))

        store.markFailed(claimed(stored), RuntimeException("late worker failure"))

        assertEquals(WorkspaceOperationStatus.SUCCEEDED, stored.status)
        assertEquals(null, stored.lastError)
        verify(operationRepository, never()).save(stored)
    }

    @Test
    fun `completion from an expired claim cannot overwrite the reclaimed attempt`() {
        val stored = operation(
            27,
            WorkspaceOperationTarget.ASSIGNMENT,
            5,
            WorkspaceOperationAction.UPDATE_ASSIGNMENT_METADATA
        ).also {
            it.status = WorkspaceOperationStatus.PROCESSING
            it.attempts = 2
        }
        val expiredClaim = ClaimedWorkspaceOperation(
            stored.id,
            stored.targetType,
            stored.targetId,
            stored.action,
            stored.artifactId,
            stored.desiredRevision,
            stored.idempotencyKey,
            1
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))

        store.markSucceeded(expiredClaim)
        store.markFailed(expiredClaim, RuntimeException("stale failure"))
        store.defer(expiredClaim)

        assertEquals(WorkspaceOperationStatus.PROCESSING, stored.status)
        assertEquals(2, stored.attempts)
        verify(operationRepository, never()).save(stored)
    }

    @Test
    fun `failed stale assignment provision does not overwrite deletion state`() {
        val assignment = assignment().also { it.lifecycleStatus = AssignmentLifecycleStatus.DELETING }
        val stored = operation(
            28,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.PROVISION_ASSIGNMENT
        ).also {
            it.status = WorkspaceOperationStatus.PROCESSING
            it.attempts = 3
        }
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))

        store.markFailed(claimed(stored), RuntimeException("stale provision failure"))

        assertEquals(AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        assertEquals(null, assignment.lastError)
        assertEquals(WorkspaceOperationStatus.FAILED, stored.status)
        verify(assignmentRepository, never()).save(assignment)
    }

    @Test
    fun `failed stale membership provision does not overwrite pending deletion`() {
        val course = course()
        val user = User(id = 41, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 31,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.DELETE_PENDING
        )
        val stored = operation(
            29,
            WorkspaceOperationTarget.MEMBERSHIP,
            membership.id,
            WorkspaceOperationAction.PROVISION_MEMBERSHIP
        ).also {
            it.status = WorkspaceOperationStatus.PROCESSING
            it.attempts = 3
        }
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(membershipRepository.findById(membership.id)).thenReturn(Optional.of(membership))

        store.markFailed(claimed(stored), RuntimeException("stale membership failure"))

        assertEquals(MembershipStatus.DELETE_PENDING, membership.lifecycleStatus)
        assertEquals(null, membership.lastError)
        verify(membershipRepository, never()).save(membership)
    }

    @Test
    fun `failed stale JCode provision does not overwrite pending deletion`() {
        val course = course()
        val user = User(id = 41, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 31,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val jcode = Jcode(
            id = 51,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.DELETE_PENDING
        )
        val stored = operation(
            30,
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE
        ).also {
            it.status = WorkspaceOperationStatus.PROCESSING
            it.attempts = 3
        }
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(jcodeRepository.findById(jcode.id)).thenReturn(Optional.of(jcode))

        store.markFailed(claimed(stored), RuntimeException("stale JCode failure"))

        assertEquals(JcodeLifecycleStatus.DELETE_PENDING, jcode.lifecycleStatus)
        assertEquals(null, jcode.lastError)
        assertEquals(JcodeObservedStatus.UNKNOWN, jcode.observedStatus)
        verify(jcodeRepository, never()).save(jcode)
    }

    @Test
    fun `failed older access revision cannot mark the newer revision failed`() {
        val course = course()
        val user = User(id = 41, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 31,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val jcode = Jcode(
            id = 51,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.READY,
            desiredRevision = 8,
            observedStatus = JcodeObservedStatus.READY
        )
        val stored = WorkspaceOperation(
            id = 31,
            targetType = WorkspaceOperationTarget.JCODE,
            targetId = jcode.id,
            action = WorkspaceOperationAction.RECONCILE_JCODE_ACCESS,
            desiredRevision = 7,
            status = WorkspaceOperationStatus.PROCESSING,
            attempts = 3
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(jcodeRepository.findById(jcode.id)).thenReturn(Optional.of(jcode))

        store.markFailed(claimed(stored), RuntimeException("stale revision failure"))

        assertEquals(JcodeObservedStatus.READY, jcode.observedStatus)
        assertEquals(null, jcode.lastError)
        verify(jcodeRepository, never()).save(jcode)
    }

    @Test
    fun `starter publication uses a second stable pass before restoring student access`() {
        val course = course()
        val assignment = assignment().copy(course = course).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.starterDistributionPending = true
        }
        val artifact = StarterArtifact(
            id = 22,
            assignment = assignment,
            version = 2,
            artifactKey = "assignments/5/starter/v2.zip",
            overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
        )
        val included = UserCourses(
            id = 31,
            course = course,
            user = User(id = 41, email = "included@example.com", role = RoleType.STUDENT, studentNum = 20260001),
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val missing = UserCourses(
            id = 32,
            course = course,
            user = User(id = 42, email = "missing@example.com", role = RoleType.STUDENT, studentNum = 20260002),
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.PROVISIONING
        )
        val stored = WorkspaceOperation(
            id = 23,
            targetType = WorkspaceOperationTarget.ASSIGNMENT,
            targetId = assignment.id,
            action = WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = artifact.id,
            status = WorkspaceOperationStatus.PROCESSING
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(membershipRepository.findByCourseId(course.id)).thenReturn(listOf(included, missing))

        store.markSucceeded(
            claimed(stored),
            mapOf("processed_students" to listOf("alg-1-20260001"))
        )

        assertNotNull(artifact.publishedAt)
        assertTrue(assignment.starterDistributionPending)
        assertEquals(WorkspaceOperationStatus.PENDING, stored.status)
        verify(workspacePolicyRevisionService, never()).bump(course.id)
        val captured = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository, times(2)).save(captured.capture())
        assertEquals(WorkspaceOperationTarget.MEMBERSHIP, captured.allValues.first().targetType)
        assertEquals(missing.id, captured.allValues.first().targetId)
        assertEquals(WorkspaceOperationAction.PROVISION_MEMBERSHIP, captured.allValues.first().action)
        assertEquals(WorkspaceOperationStatus.PENDING, captured.allValues.last().status)

        org.mockito.Mockito.clearInvocations(operationRepository, workspacePolicyRevisionService)
        stored.status = WorkspaceOperationStatus.PROCESSING
        stored.attempts = 1
        store.markSucceeded(
            claimed(stored),
            mapOf("processed_students" to listOf("alg-1-20260001", "alg-1-20260002"))
        )

        assertFalse(assignment.starterDistributionPending)
        assertEquals(WorkspaceOperationStatus.SUCCEEDED, stored.status)
        verify(workspacePolicyRevisionService).bump(course.id)
    }

    @Test
    fun `late starter completion does not alter an assignment being deleted`() {
        val course = course()
        val assignment = assignment().copy(course = course).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.starterDistributionPending = false
            it.hasStarterCode = false
        }
        val artifact = StarterArtifact(
            id = 32,
            assignment = assignment,
            version = 2,
            artifactKey = "assignments/5/starter/v2.zip",
            overwritePolicy = StarterOverwritePolicy.REPLACE_ALL
        )
        val stored = WorkspaceOperation(
            id = 33,
            targetType = WorkspaceOperationTarget.ASSIGNMENT,
            targetId = assignment.id,
            action = WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = artifact.id,
            status = WorkspaceOperationStatus.PROCESSING,
            attempts = 1
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))

        store.markSucceeded(claimed(stored), mapOf("processed_students" to emptyList<String>()))

        assertEquals(AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        assertFalse(assignment.starterDistributionPending)
        assertFalse(assignment.hasStarterCode)
        assertEquals(WorkspaceOperationStatus.SUCCEEDED, stored.status)
        verify(assignmentRepository, never()).save(assignment)
        verify(workspacePolicyRevisionService, never()).bump(course.id)
    }

    @Test
    fun `accepted starter distribution finishes while course is terminating`() {
        val course = course().also { it.status = CourseStatus.TERMINATING }
        val assignment = assignment().copy(course = course).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.starterDistributionPending = true
            it.hasStarterCode = true
        }
        val artifact = StarterArtifact(
            id = 34,
            assignment = assignment,
            version = 3,
            artifactKey = "assignments/5/starter/v3.zip",
            overwritePolicy = StarterOverwritePolicy.REPLACE_ALL,
            status = StarterArtifactStatus.READY,
            publishedAt = LocalDateTime.now()
        )
        val stored = WorkspaceOperation(
            id = 35,
            targetType = WorkspaceOperationTarget.ASSIGNMENT,
            targetId = assignment.id,
            action = WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = artifact.id,
            status = WorkspaceOperationStatus.PROCESSING,
            attempts = 1
        )
        `when`(operationRepository.findById(stored.id)).thenReturn(Optional.of(stored))
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(membershipRepository.findByCourseId(course.id)).thenReturn(emptyList())

        store.markSucceeded(claimed(stored), mapOf("processed_students" to emptyList<String>()))

        assertFalse(assignment.starterDistributionPending)
        assertEquals(WorkspaceOperationStatus.SUCCEEDED, stored.status)
        verify(workspacePolicyRevisionService).bump(course.id)
    }

    @Test
    fun `older failed starter distribution cannot be retried after a newer one succeeded`() {
        val assignment = assignment()
        val artifact = StarterArtifact(
            id = 23,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/5/starter/v1.zip",
            overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
        )
        val failed = WorkspaceOperation(
            id = 24,
            targetType = WorkspaceOperationTarget.ASSIGNMENT,
            targetId = assignment.id,
            action = WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = artifact.id,
            status = WorkspaceOperationStatus.FAILED
        )
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndStatusIn(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(false)
        `when`(operationRepository.findTopByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationStatus.FAILED
        )).thenReturn(failed)
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusInAndIdGreaterThan(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            setOf(WorkspaceOperationStatus.SUCCEEDED, WorkspaceOperationStatus.SUPERSEDED),
            failed.id
        )).thenReturn(true)
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(artifactRepository.findTopByAssignmentIdOrderByVersionDesc(assignment.id)).thenReturn(artifact)

        assertFalse(store.retry(WorkspaceOperationTarget.ASSIGNMENT, assignment.id))
        assertEquals(WorkspaceOperationStatus.SUPERSEDED, failed.status)
    }

    @Test
    fun `failed starter distribution may be retried ahead of a later finalization`() {
        val assignment = assignment().also { it.starterDistributionPending = true }
        val artifact = StarterArtifact(
            id = 25,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/5/starter/v1.zip",
            overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
        )
        val failed = WorkspaceOperation(
            id = 26,
            targetType = WorkspaceOperationTarget.ASSIGNMENT,
            targetId = assignment.id,
            action = WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = artifact.id,
            status = WorkspaceOperationStatus.FAILED
        )
        `when`(operationRepository.findTopByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationStatus.FAILED
        )).thenReturn(failed)
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndStatusIn(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(true)
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndActionAndStatusInAndIdGreaterThan(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            setOf(WorkspaceOperationStatus.SUCCEEDED, WorkspaceOperationStatus.SUPERSEDED),
            failed.id
        )).thenReturn(false)
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(artifactRepository.findTopByAssignmentIdOrderByVersionDesc(assignment.id)).thenReturn(artifact)

        assertTrue(store.retry(WorkspaceOperationTarget.ASSIGNMENT, assignment.id))
        assertEquals(WorkspaceOperationStatus.PENDING, failed.status)
    }

    @Test
    fun `older failed starter distribution cannot preempt a newer pending artifact`() {
        val assignment = assignment().also { it.starterDistributionPending = true }
        val oldArtifact = StarterArtifact(
            id = 27,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/5/starter/v1.zip",
            overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
        )
        val newArtifact = StarterArtifact(
            id = 28,
            assignment = assignment,
            version = 2,
            artifactKey = "assignments/5/starter/v2.zip",
            overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
        )
        val failed = WorkspaceOperation(
            id = 29,
            targetType = WorkspaceOperationTarget.ASSIGNMENT,
            targetId = assignment.id,
            action = WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = oldArtifact.id,
            status = WorkspaceOperationStatus.FAILED
        )
        `when`(operationRepository.findTopByTargetTypeAndTargetIdAndStatusOrderByCreatedAtDesc(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationStatus.FAILED
        )).thenReturn(failed)
        `when`(operationRepository.existsByTargetTypeAndTargetIdAndStatusIn(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            setOf(WorkspaceOperationStatus.PENDING, WorkspaceOperationStatus.PROCESSING)
        )).thenReturn(true)
        `when`(assignmentRepository.findById(assignment.id)).thenReturn(Optional.of(assignment))
        `when`(artifactRepository.findById(oldArtifact.id)).thenReturn(Optional.of(oldArtifact))
        `when`(artifactRepository.findTopByAssignmentIdOrderByVersionDesc(assignment.id)).thenReturn(newArtifact)

        assertFalse(store.retry(WorkspaceOperationTarget.ASSIGNMENT, assignment.id))
        assertEquals(WorkspaceOperationStatus.SUPERSEDED, failed.status)
    }

    private fun claimed(operation: WorkspaceOperation): ClaimedWorkspaceOperation {
        if (operation.status == WorkspaceOperationStatus.PENDING) {
            operation.status = WorkspaceOperationStatus.PROCESSING
        }
        if (operation.attempts == 0) operation.attempts = 1
        return ClaimedWorkspaceOperation(
            operation.id,
            operation.targetType,
            operation.targetId,
            operation.action,
            operation.artifactId,
            operation.desiredRevision,
            operation.idempotencyKey,
            operation.attempts
        )
    }

    private fun operation(id: Long, target: WorkspaceOperationTarget, targetId: Long, action: WorkspaceOperationAction) =
        WorkspaceOperation(id = id, targetType = target, targetId = targetId, action = action)

    private fun course() = Course(
        id = 1,
        name = "Algorithms",
        infrastructureKey = "ALG",
        year = 2026,
        term = 1,
        professor = "Professor",
        clss = 1,
        vnc = false,
        courseKey = "key"
    )

    private fun assignment() = Assignment(
        id = 5,
        course = course(),
        name = "Homework",
        description = null,
        kickoffDate = java.time.LocalDateTime.now().minusDays(1),
        deadlineDate = java.time.LocalDateTime.now().plusDays(1)
    )
}
