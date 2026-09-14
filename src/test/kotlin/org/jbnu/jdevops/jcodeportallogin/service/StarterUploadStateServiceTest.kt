package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.jbnu.jdevops.jcodeportallogin.repo.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.Optional

class StarterUploadStateServiceTest {
    private val assignmentRepository = mock(AssignmentRepository::class.java)
    private val courseRepository = mock(CourseRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val membershipRepository = mock(UserCoursesRepository::class.java)
    private val artifactRepository = mock(StarterArtifactRepository::class.java)
    private val jcodeRepository = mock(JCodeRepository::class.java)
    private val operationStore = mock(WorkspaceOperationStore::class.java)
    private val policyRevisionService = mock(WorkspacePolicyRevisionService::class.java)
    private val redisService = mock(RedisService::class.java)
    private val service = StarterUploadStateService(
        assignmentRepository,
        courseRepository,
        userRepository,
        membershipRepository,
        artifactRepository,
        jcodeRepository,
        operationStore,
        policyRevisionService,
        redisService,
        10
    )

    @Test
    fun `a recent upload reservation prevents a concurrent upload`() {
        val admin = User(id = 1, email = "admin@example.com", role = RoleType.ADMIN, studentNum = 1)
        val course = course()
        val assignment = assignment(course)
        val uploading = artifact(assignment).also { it.status = StarterArtifactStatus.UPLOADING }
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))
        `when`(artifactRepository.findTopByAssignmentIdAndStatusOrderByVersionDesc(
            assignment.id,
            StarterArtifactStatus.UPLOADING
        )).thenReturn(uploading)

        val error = assertThrows<PublicApiException> {
            service.reserve(
                course.id,
                assignment.id,
                StarterOverwritePolicy.PRESERVE_EXISTING,
                true,
                admin.email
            )
        }

        assertEquals("STARTER_UPLOAD_PENDING", error.errorCode)
        verify(artifactRepository, never()).findTopByAssignmentIdOrderByVersionDesc(assignment.id)
    }

    @Test
    fun `upload completion is archived if the assignment changed while bytes were transferred`() {
        val course = course().also { it.status = CourseStatus.TERMINATING }
        val assignment = assignment(course).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
        }
        val artifact = artifact(assignment)
        val reservation = reservation(course, assignment, artifact)
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))

        val result = service.complete(reservation, "0".repeat(64), 128, true)

        assertEquals("ASSIGNMENT_CHANGED_DURING_STARTER_UPLOAD", result.rejectionCode)
        assertEquals(StarterArtifactStatus.ARCHIVED, artifact.status)
        assertFalse(assignment.hasStarterCode)
        verify(operationStore, never()).enqueue(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifact.id,
            course.workspacePolicyRevision
        )
    }

    @Test
    fun `late initial upload failure does not overwrite assignment deletion state`() {
        val course = course()
        val assignment = assignment(course).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.starterDistributionPending = false
        }
        val artifact = artifact(assignment)
        val reservation = StarterUploadReservation(
            courseId = course.id,
            namespace = "jcode-alg-1",
            assignmentId = assignment.id,
            artifactId = artifact.id,
            version = artifact.version,
            artifactKey = artifact.artifactKey,
            reservedInitialUpload = true
        )
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))

        service.markUploadFailed(reservation, IllegalStateException("late failure"))

        assertEquals(StarterArtifactStatus.FAILED, artifact.status)
        assertEquals(null, assignment.lastError)
        verify(assignmentRepository, never()).save(assignment)
    }

    @Test
    fun `deploy completion revokes inspectors before queuing starter distribution`() {
        val course = course()
        val assignment = assignment(course)
        val artifact = artifact(assignment)
        val user = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 3,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val inspector = Jcode(
            id = 4,
            userCourse = membership,
            course = course,
            user = user,
            assignment = assignment,
            kind = JcodeKind.INSPECTOR,
            lifecycleStatus = JcodeLifecycleStatus.READY
        )
        val reservation = reservation(course, assignment, artifact)
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))
        `when`(artifactRepository.findById(artifact.id)).thenReturn(Optional.of(artifact))
        `when`(jcodeRepository.findByAssignmentId(assignment.id)).thenReturn(listOf(inspector))
        `when`(policyRevisionService.bump(course.id)).thenReturn(7)

        val result = service.complete(reservation, "0".repeat(64), 128, true)

        assertEquals(null, result.rejectionCode)
        assertEquals(StarterArtifactStatus.READY, artifact.status)
        assertTrue(assignment.hasStarterCode)
        assertTrue(assignment.starterDistributionPending)
        assertEquals(JcodeLifecycleStatus.DELETE_PENDING, inspector.lifecycleStatus)
        verify(redisService).deleteJcodeRoute(inspector.id)
        verify(operationStore).enqueue(
            WorkspaceOperationTarget.JCODE,
            inspector.id,
            WorkspaceOperationAction.DELETE_JCODE
        )
        verify(operationStore).enqueue(
            WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            WorkspaceOperationAction.DISTRIBUTE_STARTER,
            artifact.id,
            7
        )
    }

    private fun course() = Course(
        id = 10,
        name = "Algorithms",
        infrastructureKey = "ALG",
        year = 2026,
        term = 1,
        professor = "Professor",
        clss = 1,
        vnc = false,
        courseKey = "course-key",
        status = CourseStatus.ACTIVE,
        workspaceRuntimeEnabled = true
    )

    private fun assignment(course: Course) = Assignment(
        id = 20,
        course = course,
        name = "Sorting",
        description = null,
        workspaceKey = "assignment-20",
        dirName = "assignment-20",
        lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
        scheduleStatus = AssignmentScheduleStatus.OPEN,
        kickoffDate = LocalDateTime.now().minusDays(1),
        deadlineDate = LocalDateTime.now().plusDays(1)
    )

    private fun artifact(assignment: Assignment) = StarterArtifact(
        id = 30,
        assignment = assignment,
        version = 1,
        artifactKey = "assignments/20/starter/v1.zip",
        overwritePolicy = StarterOverwritePolicy.PRESERVE_EXISTING
    )

    private fun reservation(course: Course, assignment: Assignment, artifact: StarterArtifact) =
        StarterUploadReservation(
            courseId = course.id,
            namespace = "jcode-alg-1",
            assignmentId = assignment.id,
            artifactId = artifact.id,
            version = artifact.version,
            artifactKey = artifact.artifactKey,
            reservedInitialUpload = false
        )
}
