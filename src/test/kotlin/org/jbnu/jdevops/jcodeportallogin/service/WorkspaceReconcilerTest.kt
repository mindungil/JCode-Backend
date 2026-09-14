package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.entity.Assignment
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentScheduleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.Jcode
import org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.MembershipStatus
import org.jbnu.jdevops.jcodeportallogin.entity.RoleType
import org.jbnu.jdevops.jcodeportallogin.entity.StarterArtifact
import org.jbnu.jdevops.jcodeportallogin.entity.User
import org.jbnu.jdevops.jcodeportallogin.entity.UserCourses
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceScope
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDateTime

class WorkspaceReconcilerTest {
    private val operationStore = mock(WorkspaceOperationStore::class.java)
    private val accessPolicy = mock(WorkspaceAccessPolicy::class.java)
    private val contractVerifier = mock(GeneratorContractVerifier::class.java)
    private val reconciler = WorkspaceReconciler(
        operationStore,
        accessPolicy,
        contractVerifier,
        WebClient.builder().build(),
        1,
        1
    )

    @Test
    fun `access reconciliation is superseded by in-flight provisioning`() {
        val course = Course(
            id = 1,
            name = "Algorithms",
            infrastructureKey = "alg",
            year = 2026,
            term = 2,
            professor = "Professor",
            clss = 1,
            vnc = false,
            workspaceRuntimeEnabled = true,
            courseKey = "course-key"
        )
        val user = User(id = 2, email = "student@example.com", studentNum = 20260001, role = RoleType.STUDENT)
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
            desiredRevision = 7
        )
        val operation = ClaimedWorkspaceOperation(
            id = 5,
            targetType = WorkspaceOperationTarget.JCODE,
            targetId = jcode.id,
            action = WorkspaceOperationAction.RECONCILE_JCODE_ACCESS,
            artifactId = null,
            desiredRevision = 7,
            idempotencyKey = "00000000-0000-0000-0000-000000000005",
            attempts = 1
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)

        reconciler.reconcile()

        verify(operationStore).markSucceeded(operation, mapOf("_superseded" to true))
        verify(operationStore, never()).defer(operation)
    }

    @Test
    fun `jcode provisioning waits while membership preparation is in flight`() {
        val course = course()
        val user = user()
        val membership = UserCourses(
            id = 3,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.PROVISIONING
        )
        val jcode = Jcode(
            id = 4,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.PROVISIONING
        )
        val operation = operation(
            5,
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).cancelJcodeProvision(jcode.id)
    }

    @Test
    fun `jcode provisioning is compensated when course ended after request`() {
        val course = course().also { it.status = CourseStatus.ENDED }
        val user = user()
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
            lifecycleStatus = JcodeLifecycleStatus.PROVISIONING
        )
        val operation = operation(
            5,
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)

        reconciler.reconcile()

        verify(operationStore).cancelJcodeProvision(jcode.id)
        verify(operationStore).markSucceeded(operation, null)
        verify(accessPolicy, never()).plan(jcode)
    }

    @Test
    fun `assignment jcode provisioning is compensated when assignment is no longer accessible`() {
        val course = course().copy(workspaceScope = WorkspaceScope.ASSIGNMENT)
        val user = user()
        val membership = UserCourses(
            id = 3,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            scheduleStatus = AssignmentScheduleStatus.SCHEDULED,
            kickoffDate = LocalDateTime.now().plusHours(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )
        val jcode = Jcode(
            id = 4,
            userCourse = membership,
            course = course,
            user = user,
            assignment = assignment,
            lifecycleStatus = JcodeLifecycleStatus.PROVISIONING
        )
        val operation = operation(
            5,
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)
        val reconcilerWithRealPolicy = WorkspaceReconciler(
            operationStore,
            WorkspaceAccessPolicy(mock(AssignmentRepository::class.java)),
            contractVerifier,
            WebClient.builder().build(),
            1,
            1
        )

        reconcilerWithRealPolicy.reconcile()

        verify(operationStore).cancelJcodeProvision(jcode.id)
        verify(operationStore).markSucceeded(operation, null)
    }

    @Test
    fun `access reconciliation waits while manager demotion prepares membership`() {
        val course = course()
        val user = user()
        val membership = UserCourses(
            id = 3,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.PROVISIONING
        )
        val jcode = Jcode(
            id = 4,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.READY,
            desiredRevision = 7
        )
        val operation = operation(
            5,
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.RECONCILE_JCODE_ACCESS,
            desiredRevision = 7
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
    }

    @Test
    fun `assignment archive waits for membership mutations before touching storage`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            lifecycleStatus = AssignmentLifecycleStatus.DELETING,
            scheduleStatus = AssignmentScheduleStatus.CLOSED,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now()
        )
        val operation = operation(
            9,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)
        `when`(operationStore.courseMembershipMutationsSettled(course.id)).thenReturn(false)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).loadAssignmentJcodes(assignment.id)
    }

    @Test
    fun `starter distribution without an active reservation is skipped`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().minusSeconds(1)
        )
        val operation = operation(
            9,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = 10
        )
        val artifact = StarterArtifact(
            id = 10,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/8/starter/v1.zip",
            checksum = "0".repeat(64),
            overwritePolicy = org.jbnu.jdevops.jcodeportallogin.entity.StarterOverwritePolicy.PRESERVE_EXISTING
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)
        `when`(operationStore.loadArtifact(artifact.id)).thenReturn(artifact)
        `when`(operationStore.courseMembershipMutationsSettled(course.id)).thenReturn(true)
        `when`(operationStore.assignmentInspectorsClosed(assignment.id)).thenReturn(true)
        `when`(operationStore.activeCourseJcodesReconciled(
            course.id,
            assignment.id,
            course.workspacePolicyRevision
        )).thenReturn(true)
        `when`(operationStore.beginStarterDistribution(artifact.id)).thenReturn(false)

        reconciler.reconcile()

        verify(operationStore).markSucceeded(operation, mapOf("_skipStateUpdate" to true))
        verify(operationStore).loadArtifact(artifact.id)
    }

    @Test
    fun `starter distribution waits until every student JCode has removed the assignment mount`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            starterDistributionPending = true,
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )
        val artifact = StarterArtifact(
            id = 10,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/8/starter/v1.zip",
            checksum = "0".repeat(64),
            overwritePolicy = org.jbnu.jdevops.jcodeportallogin.entity.StarterOverwritePolicy.PRESERVE_EXISTING
        )
        val operation = operation(
            9,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            desiredRevision = course.workspacePolicyRevision,
            artifactId = artifact.id
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)
        `when`(operationStore.loadArtifact(artifact.id)).thenReturn(artifact)
        `when`(operationStore.beginStarterDistribution(artifact.id)).thenReturn(true)
        `when`(operationStore.courseMembershipMutationsSettled(course.id)).thenReturn(true)
        `when`(operationStore.assignmentInspectorsClosed(assignment.id)).thenReturn(true)
        `when`(operationStore.activeCourseJcodesReconciled(
            course.id,
            assignment.id,
            course.workspacePolicyRevision
        )).thenReturn(false)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).markSucceeded(operation, null)
    }

    @Test
    fun `starter distribution waits until read only inspectors are fully deleted`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            starterDistributionPending = true,
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )
        val artifact = StarterArtifact(
            id = 10,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/8/starter/v1.zip",
            checksum = "0".repeat(64),
            overwritePolicy = org.jbnu.jdevops.jcodeportallogin.entity.StarterOverwritePolicy.REPLACE_ALL
        )
        val operation = operation(
            9,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            desiredRevision = course.workspacePolicyRevision,
            artifactId = artifact.id
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)
        `when`(operationStore.loadArtifact(artifact.id)).thenReturn(artifact)
        `when`(operationStore.beginStarterDistribution(artifact.id)).thenReturn(true)
        `when`(operationStore.courseMembershipMutationsSettled(course.id)).thenReturn(true)
        `when`(operationStore.assignmentInspectorsClosed(assignment.id)).thenReturn(false)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).activeCourseJcodesReconciled(
            course.id,
            assignment.id,
            course.workspacePolicyRevision
        )
    }

    @Test
    fun `starter distribution waits for membership joins and removals`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            starterDistributionPending = true,
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )
        val artifact = StarterArtifact(
            id = 10,
            assignment = assignment,
            version = 1,
            artifactKey = "assignments/8/starter/v1.zip",
            checksum = "0".repeat(64),
            overwritePolicy = org.jbnu.jdevops.jcodeportallogin.entity.StarterOverwritePolicy.PRESERVE_EXISTING
        )
        val operation = operation(
            9,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            desiredRevision = course.workspacePolicyRevision,
            artifactId = artifact.id
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)
        `when`(operationStore.loadArtifact(artifact.id)).thenReturn(artifact)
        `when`(operationStore.beginStarterDistribution(artifact.id)).thenReturn(true)
        `when`(operationStore.courseMembershipMutationsSettled(course.id)).thenReturn(false)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).activeCourseJcodesReconciled(
            course.id,
            assignment.id,
            course.workspacePolicyRevision
        )
    }

    @Test
    fun `starter distribution waits for failed assignment provisioning to be retried`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            starterDistributionPending = true,
            lifecycleStatus = AssignmentLifecycleStatus.PROVISION_FAILED,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )
        val operation = operation(
            10,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifactId = 12
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).loadArtifact(12)
    }

    @Test
    fun `final submission waits while starter distribution is unresolved`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            starterDistributionPending = true,
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            scheduleStatus = AssignmentScheduleStatus.CLOSED,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().minusSeconds(1)
        )
        val operation = operation(
            11,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).courseMembershipMutationsSettled(course.id)
    }

    @Test
    fun `assignment provisioning is compensated before generator call when course has ended`() {
        val course = course().also { it.status = CourseStatus.TERMINATING }
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )
        val operation = operation(
            12,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.PROVISION_ASSIGNMENT
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)

        reconciler.reconcile()

        verify(operationStore).cancelAssignmentProvision(assignment.id)
        verify(operationStore).markSucceeded(operation, mapOf("_skipStateUpdate" to true))
        verify(contractVerifier, never()).requireCompatible()
    }

    @Test
    fun `assignment restore is compensated before generator call when course has ended`() {
        val course = course().also { it.status = CourseStatus.ENDED }
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            finalizedAt = LocalDateTime.now().minusMinutes(1),
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )
        val operation = operation(
            13,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.RESTORE_ASSIGNMENT
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)

        reconciler.reconcile()

        verify(operationStore).cancelAssignmentProvision(assignment.id)
        verify(operationStore).markSucceeded(operation, mapOf("_skipStateUpdate" to true))
        verify(contractVerifier, never()).requireCompatible()
    }

    @Test
    fun `assignment restore is cancelled when its new deadline passed in the queue`() {
        val course = course()
        val assignment = Assignment(
            id = 8,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-8",
            dirName = "assignment-8",
            lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            finalizedAt = LocalDateTime.now().minusMinutes(2),
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().minusMinutes(1)
        )
        val operation = operation(
            14,
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.RESTORE_ASSIGNMENT
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)

        reconciler.reconcile()

        verify(operationStore).cancelExpiredAssignmentRestore(assignment.id)
        verify(operationStore).markSucceeded(operation, mapOf("_skipStateUpdate" to true))
        verify(contractVerifier, never()).requireCompatible()
    }

    private fun operation(
        id: Long,
        target: WorkspaceOperationTarget,
        targetId: Long,
        action: WorkspaceOperationAction,
        desiredRevision: Long? = null,
        artifactId: Long? = null
    ) = ClaimedWorkspaceOperation(
        id,
        target,
        targetId,
        action,
        artifactId,
        desiredRevision,
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}",
        1
    )

    private fun course() = Course(
        id = 1,
        name = "Algorithms",
        infrastructureKey = "alg",
        year = 2026,
        term = 2,
        professor = "Professor",
        clss = 1,
        vnc = false,
        workspaceRuntimeEnabled = true,
        workspacePolicyRevision = 7,
        courseKey = "course-key"
    )

    private fun user() = User(
        id = 2,
        email = "student@example.com",
        studentNum = 20260001,
        role = RoleType.STUDENT
    )
}
