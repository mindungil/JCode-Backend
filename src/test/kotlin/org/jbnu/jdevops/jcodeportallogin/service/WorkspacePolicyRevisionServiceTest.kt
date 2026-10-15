package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.Assignment
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.Jcode
import org.jbnu.jdevops.jcodeportallogin.entity.JcodeKind
import org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.MembershipStatus
import org.jbnu.jdevops.jcodeportallogin.entity.RoleType
import org.jbnu.jdevops.jcodeportallogin.entity.User
import org.jbnu.jdevops.jcodeportallogin.entity.UserCourses
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperation
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.WorkspaceOperationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.Optional

class WorkspacePolicyRevisionServiceTest {
    private val courseRepository = mock(CourseRepository::class.java)
    private val jCodeRepository = mock(JCodeRepository::class.java)
    private val operationRepository = mock(WorkspaceOperationRepository::class.java)
    private val redisCalls = mutableListOf<Pair<String, List<Any?>>>()
    private val redisService = mock(RedisService::class.java) { invocation ->
        redisCalls += invocation.method.name to invocation.arguments.toList()
        null
    }
    private val service = WorkspacePolicyRevisionService(
        courseRepository,
        jCodeRepository,
        operationRepository,
        redisService
    )

    @Test
    fun `policy bump invalidates assignment workspace but not inspector`() {
        val course = Course(
            id = 1,
            name = "Algorithms",
            infrastructureKey = "algo",
            year = 2026,
            term = 2,
            professor = "Professor",
            clss = 1,
            vnc = false,
            courseKey = "course-key",
            workspacePolicyRevision = 4
        )
        val user = User(id = 2, email = "student@example.com", studentNum = 20260001, role = RoleType.STUDENT)
        val membership = UserCourses(
            id = 3,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val assignment = Assignment(
            id = 4,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-4",
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            kickoffDate = LocalDateTime.now().minusHours(1),
            deadlineDate = LocalDateTime.now().plusHours(1)
        )
        val studentWorkspace = Jcode(
            id = 11,
            userCourse = membership,
            course = course,
            user = user,
            assignment = assignment,
            lifecycleStatus = JcodeLifecycleStatus.READY,
            desiredRevision = 4
        )
        val inspector = Jcode(
            id = 12,
            userCourse = membership,
            course = course,
            user = user,
            assignment = assignment,
            kind = JcodeKind.INSPECTOR,
            lifecycleStatus = JcodeLifecycleStatus.READY,
            desiredRevision = 4
        )

        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(jCodeRepository.findByCourseId(course.id)).thenReturn(listOf(studentWorkspace, inspector))
        `when`(operationRepository.existsByIdempotencyKey(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(false)

        assertEquals(5, service.bump(course.id))

        assertEquals(5, studentWorkspace.desiredRevision)
        assertEquals(4, inspector.desiredRevision)
        // The open IDE is warned first; its route is revoked by the delayed reconcile.
        assertTrue(redisCalls.none { it.first == "deleteJcodeRoute" })
        val notice = redisCalls.single { it.first == "storeRestartNotice" }.second
        assertEquals(listOf<Any?>(studentWorkspace.id, "5", PolicyChangeReason.POLICY_CHANGED), notice.take(3))
        val operation = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository).save(operation.capture())
        assertEquals(WorkspaceOperationAction.RECONCILE_JCODE_ACCESS, operation.value.action)
        assertEquals(studentWorkspace.id, operation.value.targetId)
        assertEquals(5, operation.value.desiredRevision)
        assertTrue(operation.value.nextAttemptAt.isAfter(LocalDateTime.now().plusSeconds(3)))
    }

    @Test
    fun `workspaces whose mounts do not change keep their session`() {
        val course = Course(
            id = 31, name = "Algorithms", infrastructureKey = "algo", year = 2026, term = 2,
            professor = "Professor", clss = 1, vnc = false, courseKey = "course-key", workspacePolicyRevision = 8
        )
        val user = User(id = 32, email = "prof@example.com", studentNum = 1, role = RoleType.PROFESSOR)
        val membership = UserCourses(
            id = 33, course = course, user = user, role = RoleType.PROFESSOR, lifecycleStatus = MembershipStatus.READY
        )
        val managerWorkspace = Jcode(
            id = 34, userCourse = membership, course = course, user = user,
            lifecycleStatus = JcodeLifecycleStatus.READY, desiredRevision = 8,
            observedRevision = 8, observedMountHash = "same"
        )
        val policy = mock(WorkspaceAccessPolicy::class.java) { invocation ->
            if (invocation.method.name == "plan") WorkspaceAccessPlan(9, "same", emptyList(), null)
            else org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val keeping = WorkspacePolicyRevisionService(courseRepository, jCodeRepository, operationRepository, redisService, policy, 5)
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(jCodeRepository.findByCourseId(course.id)).thenReturn(listOf(managerWorkspace))
        `when`(operationRepository.existsByIdempotencyKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(false)

        keeping.bump(course.id, PolicyChangeReason.ASSIGNMENT_CREATED)

        assertTrue(redisCalls.isEmpty(), "no restart notice and no route revocation")
        val operation = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository).save(operation.capture())
        assertFalse(operation.value.nextAttemptAt.isAfter(LocalDateTime.now().plusSeconds(1)))
    }

    @Test
    fun `existing active course is initialized once and all standard routes are revoked`() {
        val course = Course(
            id = 21,
            name = "Data Structures",
            infrastructureKey = "data",
            year = 2026,
            term = 2,
            professor = "Professor",
            clss = 1,
            vnc = false,
            workspaceRuntimeEnabled = true,
            workspacePolicyInitialized = false,
            courseKey = "course-key"
        )
        val user = User(id = 22, email = "student@example.com", studentNum = 20260002, role = RoleType.STUDENT)
        val membership = UserCourses(
            id = 23,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        val workspace = Jcode(
            id = 24,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.READY
        )
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(jCodeRepository.findByCourseId(course.id)).thenReturn(listOf(workspace))
        `when`(operationRepository.existsByIdempotencyKey(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(false)

        assertTrue(service.initializeExistingCourse(course.id))
        assertEquals(1, course.workspacePolicyRevision)
        assertTrue(course.workspacePolicyInitialized)
        assertEquals(1, workspace.desiredRevision)
        assertEquals(listOf("deleteJcodeRoute" to listOf<Any?>(workspace.id)), redisCalls)

        assertFalse(service.initializeExistingCourse(course.id))
        assertEquals(1, redisCalls.size)
    }
}
