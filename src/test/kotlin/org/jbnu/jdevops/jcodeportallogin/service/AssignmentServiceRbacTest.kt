package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.Assignment
import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentScheduleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.MembershipStatus
import org.jbnu.jdevops.jcodeportallogin.entity.RoleType
import org.jbnu.jdevops.jcodeportallogin.entity.StarterArtifactStatus
import org.jbnu.jdevops.jcodeportallogin.entity.User
import org.jbnu.jdevops.jcodeportallogin.entity.UserCourses
import org.jbnu.jdevops.jcodeportallogin.entity.Jcode
import org.jbnu.jdevops.jcodeportallogin.entity.JcodeKind
import org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserCoursesRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserRepository
import org.jbnu.jdevops.jcodeportallogin.repo.StarterArtifactRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.Optional

class AssignmentServiceRbacTest {
    private val assignmentRepository = mock(AssignmentRepository::class.java)
    private val courseRepository = mock(CourseRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val userCoursesRepository = mock(UserCoursesRepository::class.java)
    private val starterArtifactRepository = mock(StarterArtifactRepository::class.java)
    private val jCodeRepository = mock(JCodeRepository::class.java)
    private val workspaceOperationStore = mock(WorkspaceOperationStore::class.java)
    private val workspacePolicyRevisionService = mock(WorkspacePolicyRevisionService::class.java)
    private val redisService = mock(RedisService::class.java)
    private val generatorContractVerifier = mock(GeneratorContractVerifier::class.java)
    private val starterUploadStateService = mock(StarterUploadStateService::class.java)
    private val generatorWebClient = WebClient.builder().build()

    private val service = AssignmentService(
        assignmentRepository,
        courseRepository,
        userRepository,
        userCoursesRepository,
        starterArtifactRepository,
        jCodeRepository,
        workspaceOperationStore,
        workspacePolicyRevisionService,
        redisService,
        generatorContractVerifier,
        starterUploadStateService,
        generatorWebClient,
        10,
        120
    )

    @Test
    fun `assistant in another course cannot delete assignment`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val targetCourseId = 20L

        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, targetCourseId)).thenReturn(null)

        val ex = assertThrows<ResponseStatusException> {
            service.deleteAssignment(targetCourseId, 100L, assistant.email)
        }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(assignmentRepository, never()).save(org.mockito.ArgumentMatchers.any(Assignment::class.java))
    }

    @Test
    fun `global professor without course professor membership cannot manage assignment`() {
        val professor = user(1, "professor@example.com", RoleType.PROFESSOR)
        `when`(userRepository.findByEmail(professor.email)).thenReturn(professor)
        `when`(userCoursesRepository.findByUserIdAndCourseId(professor.id, 20L)).thenReturn(null)

        val ex = assertThrows<ResponseStatusException> {
            service.deleteAssignment(20L, 100L, professor.email)
        }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(assignmentRepository, never()).save(org.mockito.ArgumentMatchers.any(Assignment::class.java))
    }

    @Test
    fun `course assistant can delete assignment in managed course`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val courseId = 10L
        val assignmentId = 100L

        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        val course = course(courseId)
        val assignment = assignment(assignmentId, course, "title", "dir")
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, courseId))
            .thenReturn(userCourse(assistant, course, RoleType.ASSISTANT))
        `when`(courseRepository.findById(courseId)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignmentId, courseId)).thenReturn(Optional.of(assignment))

        service.deleteAssignment(courseId, assignmentId, assistant.email)

        assertEquals(org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus.DELETING, assignment.lifecycleStatus)
        verify(assignmentRepository).save(assignment)
        verify(workspaceOperationStore).enqueue(
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction.ARCHIVE_ASSIGNMENT
        )
    }

    @Test
    fun `course assistant can update assignment display title without changing dir name`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val course = course(10)
        val assignment = assignment(100, course, "old title", "old-dir").also {
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
        }
        val dto = org.jbnu.jdevops.jcodeportallogin.dto.assignment.AssignmentDto(
            assignmentName = "new title",
            assignmentDescription = "updated",
            kickoffDate = assignment.kickoffDate,
            deadlineDate = assignment.deadlineDate
        )

        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, course.id))
            .thenReturn(userCourse(assistant, course, RoleType.ASSISTANT))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id)).thenReturn(Optional.of(assignment))
        `when`(assignmentRepository.save(org.mockito.ArgumentMatchers.any(Assignment::class.java)))
            .thenAnswer { it.arguments[0] as Assignment }

        val result = service.updateAssignment(course.id, assignment.id, dto, assistant.email)

        assertEquals("new title", result.assignmentName)
        assertEquals("old-dir", result.dirName)
        assert(result.updatedAt != assignment.updatedAt.toString())
        verify(workspacePolicyRevisionService).bump(course.id)
    }


    @Test
    fun `deadline extension invalidates routes even when persistence merges into managed entity`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val course = course(10)
        val assignment = assignment(100, course, "title", "assignment-100").also {
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
        }
        val extendedDeadline = assignment.deadlineDate.plusHours(1)
        val dto = org.jbnu.jdevops.jcodeportallogin.dto.assignment.AssignmentDto(
            assignmentName = assignment.name,
            kickoffDate = assignment.kickoffDate,
            deadlineDate = extendedDeadline
        )

        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, course.id))
            .thenReturn(userCourse(assistant, course, RoleType.ASSISTANT))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))
        `when`(assignmentRepository.save(org.mockito.ArgumentMatchers.any(Assignment::class.java)))
            .thenAnswer { invocation ->
                val update = invocation.arguments[0] as Assignment
                assignment.copy(
                    name = update.name,
                    description = update.description,
                    kickoffDate = update.kickoffDate,
                    deadlineDate = update.deadlineDate,
                    scheduleStatus = update.scheduleStatus,
                    updatedAt = update.updatedAt
                )
            }

        val result = service.updateAssignment(course.id, assignment.id, dto, assistant.email)

        assertEquals(extendedDeadline, result.deadlineDate)
        verify(workspacePolicyRevisionService).bump(course.id)
    }

    @Test
    fun `moving an open assignment start into the future deletes student assignment sessions`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val course = course(10)
        val student = user(2, "student@example.com", RoleType.STUDENT)
        val studentMembership = userCourse(student, course, RoleType.STUDENT)
        val assignment = assignment(100, course, "title", "assignment-100").also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
        }
        val studentJcode = Jcode(
            id = 200,
            userCourse = studentMembership,
            course = course,
            user = student,
            assignment = assignment,
            kind = JcodeKind.STANDARD,
            lifecycleStatus = JcodeLifecycleStatus.READY
        )
        val inspector = Jcode(
            id = 201,
            userCourse = studentMembership,
            course = course,
            user = student,
            assignment = assignment,
            kind = JcodeKind.INSPECTOR,
            lifecycleStatus = JcodeLifecycleStatus.READY
        )
        val dto = org.jbnu.jdevops.jcodeportallogin.dto.assignment.AssignmentDto(
            assignmentName = assignment.name,
            kickoffDate = LocalDateTime.now().plusHours(1),
            deadlineDate = LocalDateTime.now().plusHours(2)
        )
        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, course.id))
            .thenReturn(userCourse(assistant, course, RoleType.ASSISTANT))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))
        `when`(assignmentRepository.save(org.mockito.ArgumentMatchers.any(Assignment::class.java)))
            .thenAnswer { it.arguments[0] as Assignment }
        `when`(jCodeRepository.findByAssignmentId(assignment.id)).thenReturn(listOf(studentJcode, inspector))

        val result = service.updateAssignment(course.id, assignment.id, dto, assistant.email)

        assertEquals(AssignmentScheduleStatus.SCHEDULED, result.scheduleStatus)
        assertEquals(JcodeLifecycleStatus.DELETE_PENDING, studentJcode.lifecycleStatus)
        assertEquals(JcodeLifecycleStatus.READY, inspector.lifecycleStatus)
        verify(redisService).deleteJcodeRoute(studentJcode.id)
        verify(workspaceOperationStore).enqueue(
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget.JCODE,
            studentJcode.id,
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction.DELETE_JCODE
        )
    }

    @Test
    fun `deadline that already passed requires finalized reopen even if stored status is stale`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val course = course(10)
        val assignment = assignment(
            100,
            course,
            "title",
            "assignment-100",
            kickoffDate = LocalDateTime.now().minusHours(2),
            deadlineDate = LocalDateTime.now().minusMinutes(1)
        ).also {
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
        }
        val dto = org.jbnu.jdevops.jcodeportallogin.dto.assignment.AssignmentDto(
            assignmentName = assignment.name,
            kickoffDate = assignment.kickoffDate,
            deadlineDate = LocalDateTime.now().plusHours(1)
        )
        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, course.id))
            .thenReturn(userCourse(assistant, course, RoleType.ASSISTANT))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))

        val error = assertThrows<PublicApiException> {
            service.updateAssignment(course.id, assignment.id, dto, assistant.email)
        }

        assertEquals("ASSIGNMENT_REOPEN_REQUIRED", error.errorCode)
        verify(assignmentRepository, never()).save(org.mockito.ArgumentMatchers.any(Assignment::class.java))
    }

    @Test
    fun `assignment cannot be edited while a finalized workspace is being restored`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val course = course(10)
        val assignment = assignment(100, course, "title", "assignment-100").also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
            it.finalizedAt = LocalDateTime.now().minusMinutes(1)
        }
        val dto = org.jbnu.jdevops.jcodeportallogin.dto.assignment.AssignmentDto(
            assignmentName = assignment.name,
            kickoffDate = assignment.kickoffDate,
            deadlineDate = assignment.deadlineDate.plusHours(1)
        )
        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, course.id))
            .thenReturn(userCourse(assistant, course, RoleType.ASSISTANT))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))

        val error = assertThrows<PublicApiException> {
            service.updateAssignment(course.id, assignment.id, dto, assistant.email)
        }

        assertEquals("ASSIGNMENT_RESTORE_PENDING", error.errorCode)
        verify(assignmentRepository, never()).save(org.mockito.ArgumentMatchers.any(Assignment::class.java))
    }

    @Test
    fun `shortening deadline cancels only an unuploaded starter reservation`() {
        val assistant = user(1, "ta@example.com", RoleType.STUDENT)
        val course = course(10)
        val assignment = assignment(100, course, "title", "assignment-100").also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
            it.starterDistributionPending = true
            it.hasStarterCode = false
            it.lastError = "STARTER_UPLOAD_FAILED"
        }
        val dto = org.jbnu.jdevops.jcodeportallogin.dto.assignment.AssignmentDto(
            assignmentName = assignment.name,
            kickoffDate = LocalDateTime.now().minusHours(2),
            deadlineDate = LocalDateTime.now().minusMinutes(1)
        )
        `when`(userRepository.findByEmail(assistant.email)).thenReturn(assistant)
        `when`(userCoursesRepository.findByUserIdAndCourseId(assistant.id, course.id))
            .thenReturn(userCourse(assistant, course, RoleType.ASSISTANT))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))
        `when`(assignmentRepository.save(org.mockito.ArgumentMatchers.any(Assignment::class.java)))
            .thenAnswer { it.arguments[0] as Assignment }

        val result = service.updateAssignment(course.id, assignment.id, dto, assistant.email)

        assertEquals(AssignmentScheduleStatus.CLOSED, result.scheduleStatus)
        assertEquals(false, result.starterDistributionPending)
        assertEquals(null, result.lastError)
        verify(workspaceOperationStore).enqueue(
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
        )
    }

    @Test
    fun `schedule close releases an initial starter reservation that never uploaded`() {
        val assignment = assignment(
            102,
            course(10),
            "pending starter",
            "assignment-102",
            kickoffDate = LocalDateTime.now().minusHours(2),
            deadlineDate = LocalDateTime.now().minusMinutes(1)
        ).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
            it.starterDistributionPending = true
            it.hasStarterCode = false
            it.lastError = "STARTER_UPLOAD_FAILED"
        }
        `when`(assignmentRepository.findScheduleTransitionCandidatesForUpdate(
            eq(CourseStatus.ACTIVE) ?: CourseStatus.ACTIVE,
            eq(AssignmentLifecycleStatus.ACTIVE) ?: AssignmentLifecycleStatus.ACTIVE,
            eq(AssignmentScheduleStatus.SCHEDULED) ?: AssignmentScheduleStatus.SCHEDULED,
            eq(AssignmentScheduleStatus.OPEN) ?: AssignmentScheduleStatus.OPEN,
            eq(AssignmentScheduleStatus.CLOSED) ?: AssignmentScheduleStatus.CLOSED,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN
        )).thenReturn(listOf(assignment))

        service.refreshScheduleStatuses()

        assertEquals(AssignmentScheduleStatus.CLOSED, assignment.scheduleStatus)
        assertEquals(false, assignment.starterDistributionPending)
        assertEquals(null, assignment.lastError)
        verify(workspaceOperationStore).enqueue(
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
        )
    }

    @Test
    fun `schedule refresh only requests assignments from active courses`() {
        val assignment = assignment(
            100,
            course(10),
            "title",
            "assignment-100",
            kickoffDate = LocalDateTime.now().minusHours(2),
            deadlineDate = LocalDateTime.now().minusMinutes(1)
        ).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.OPEN
        }
        `when`(assignmentRepository.findScheduleTransitionCandidatesForUpdate(
            eq(CourseStatus.ACTIVE) ?: CourseStatus.ACTIVE,
            eq(AssignmentLifecycleStatus.ACTIVE) ?: AssignmentLifecycleStatus.ACTIVE,
            eq(AssignmentScheduleStatus.SCHEDULED) ?: AssignmentScheduleStatus.SCHEDULED,
            eq(AssignmentScheduleStatus.OPEN) ?: AssignmentScheduleStatus.OPEN,
            eq(AssignmentScheduleStatus.CLOSED) ?: AssignmentScheduleStatus.CLOSED,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN
        ))
            .thenReturn(listOf(assignment))

        service.refreshScheduleStatuses()

        verify(assignmentRepository).findScheduleTransitionCandidatesForUpdate(
            eq(CourseStatus.ACTIVE) ?: CourseStatus.ACTIVE,
            eq(AssignmentLifecycleStatus.ACTIVE) ?: AssignmentLifecycleStatus.ACTIVE,
            eq(AssignmentScheduleStatus.SCHEDULED) ?: AssignmentScheduleStatus.SCHEDULED,
            eq(AssignmentScheduleStatus.OPEN) ?: AssignmentScheduleStatus.OPEN,
            eq(AssignmentScheduleStatus.CLOSED) ?: AssignmentScheduleStatus.CLOSED,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN
        )
        assertEquals(AssignmentScheduleStatus.CLOSED, assignment.scheduleStatus)
        verify(workspaceOperationStore).enqueue(
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
        )
    }

    @Test
    fun `abandoned initial starter upload becomes recoverable instead of polling forever`() {
        val assignment = assignment(
            103,
            course(10),
            "pending starter",
            "assignment-103"
        ).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.starterDistributionPending = true
            it.hasStarterCode = false
            it.lastError = null
        }
        `when`(assignmentRepository.findExpiredStarterUploadReservationsForUpdate(
            eq(AssignmentLifecycleStatus.ACTIVE) ?: AssignmentLifecycleStatus.ACTIVE,
            eq(StarterArtifactStatus.UPLOADING) ?: StarterArtifactStatus.UPLOADING,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN
        )).thenReturn(listOf(assignment))
        `when`(assignmentRepository.findScheduleTransitionCandidatesForUpdate(
            eq(CourseStatus.ACTIVE) ?: CourseStatus.ACTIVE,
            eq(AssignmentLifecycleStatus.ACTIVE) ?: AssignmentLifecycleStatus.ACTIVE,
            eq(AssignmentScheduleStatus.SCHEDULED) ?: AssignmentScheduleStatus.SCHEDULED,
            eq(AssignmentScheduleStatus.OPEN) ?: AssignmentScheduleStatus.OPEN,
            eq(AssignmentScheduleStatus.CLOSED) ?: AssignmentScheduleStatus.CLOSED,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN
        )).thenReturn(emptyList())

        service.refreshScheduleStatuses()

        assertEquals("STARTER_UPLOAD_TIMEOUT", assignment.lastError)
        assertEquals(true, assignment.starterDistributionPending)
        verify(assignmentRepository).save(assignment)
    }

    @Test
    fun `schedule refresh finalizes legacy closed assignment exactly once`() {
        val assignment = assignment(
            101,
            course(10),
            "legacy",
            "assignment-101",
            kickoffDate = LocalDateTime.now().minusHours(2),
            deadlineDate = LocalDateTime.now().minusMinutes(1)
        ).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ACTIVE
            it.scheduleStatus = AssignmentScheduleStatus.CLOSED
            it.finalizationGeneration = 0
            it.finalizedAt = null
        }
        `when`(assignmentRepository.findScheduleTransitionCandidatesForUpdate(
            eq(CourseStatus.ACTIVE) ?: CourseStatus.ACTIVE,
            eq(AssignmentLifecycleStatus.ACTIVE) ?: AssignmentLifecycleStatus.ACTIVE,
            eq(AssignmentScheduleStatus.SCHEDULED) ?: AssignmentScheduleStatus.SCHEDULED,
            eq(AssignmentScheduleStatus.OPEN) ?: AssignmentScheduleStatus.OPEN,
            eq(AssignmentScheduleStatus.CLOSED) ?: AssignmentScheduleStatus.CLOSED,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN
        ))
            .thenReturn(listOf(assignment))

        service.refreshScheduleStatuses()
        service.refreshScheduleStatuses()

        assertEquals(1, assignment.finalizationGeneration)
        verify(workspaceOperationStore).enqueue(
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget.ASSIGNMENT,
            assignment.id,
            org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION
        )
        verify(workspacePolicyRevisionService).bump(assignment.course.id)
    }

    private fun course(id: Long) = Course(
        id = id,
        name = "Algorithms",
        infrastructureKey = "ALG",
        year = 2026,
        term = 1,
        professor = "Professor",
        clss = 1,
        vnc = false,
        courseKey = "course-key"
    )

    private fun user(id: Long, email: String, role: RoleType) = User(
        id = id,
        email = email,
        role = role,
        studentNum = id.toInt()
    )

    private fun assignment(
        id: Long,
        course: Course,
        name: String,
        dirName: String,
        kickoffDate: LocalDateTime = LocalDateTime.now().minusHours(1),
        deadlineDate: LocalDateTime = LocalDateTime.now().plusHours(1)
    ) = Assignment(
        id = id,
        course = course,
        name = name,
        description = "description",
        dirName = dirName,
        kickoffDate = kickoffDate,
        deadlineDate = deadlineDate
    )

    private fun userCourse(user: User, course: Course, role: RoleType) = UserCourses(
        id = user.id,
        user = user,
        course = course,
        role = role,
        lifecycleStatus = MembershipStatus.READY
    )
}
