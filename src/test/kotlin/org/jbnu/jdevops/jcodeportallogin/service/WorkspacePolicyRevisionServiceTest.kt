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
    private val redisService = mock(RedisService::class.java)
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
        verify(redisService).deleteJcodeRoute(studentWorkspace.id)
        verify(redisService, never()).deleteJcodeRoute(inspector.id)
        val operation = ArgumentCaptor.forClass(WorkspaceOperation::class.java)
        verify(operationRepository).save(operation.capture())
        assertEquals(WorkspaceOperationAction.RECONCILE_JCODE_ACCESS, operation.value.action)
        assertEquals(studentWorkspace.id, operation.value.targetId)
        assertEquals(5, operation.value.desiredRevision)
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
        verify(redisService).deleteJcodeRoute(workspace.id)

        assertFalse(service.initializeExistingCourse(course.id))
        verify(redisService).deleteJcodeRoute(workspace.id)
    }
}
