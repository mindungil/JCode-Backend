package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.Assignment
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentScheduleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.entity.MembershipStatus
import org.jbnu.jdevops.jcodeportallogin.entity.RoleType
import org.jbnu.jdevops.jcodeportallogin.entity.User
import org.jbnu.jdevops.jcodeportallogin.entity.UserCourses
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserCoursesRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserRepository
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.Optional

class JCodeServiceSecurityTest {
    private val jcodeRepository = mock(JCodeRepository::class.java)
    private val courseRepository = mock(CourseRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val membershipRepository = mock(UserCoursesRepository::class.java)
    private val assignmentRepository = mock(AssignmentRepository::class.java)
    private val operationStore = mock(WorkspaceOperationStore::class.java)
    private val redisService = mock(RedisService::class.java)
    private val accessPolicy = mock(WorkspaceAccessPolicy::class.java)
    private val service = JCodeService(
        jcodeRepository,
        courseRepository,
        userRepository,
        membershipRepository,
        assignmentRepository,
        operationStore,
        redisService,
        accessPolicy
    )

    @Test
    fun `admin cannot create a writable standard JCode for another user`() {
        val course = course()
        val admin = User(id = 1, email = "admin@example.com", role = RoleType.ADMIN, studentNum = 1)
        val student = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = membership(student, course)
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(userRepository.findByEmail(student.email)).thenReturn(student)
        `when`(membershipRepository.findByUserIdAndCourseIdForUpdate(student.id, course.id)).thenReturn(membership)

        val error = assertThrows<ResponseStatusException> {
            service.createJCode(course.id, student.email, admin.email, "token", false)
        }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        verify(jcodeRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `inspector cannot start while starter distribution is pending`() {
        val course = course()
        val admin = User(id = 1, email = "admin@example.com", role = RoleType.ADMIN, studentNum = 1)
        val student = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = membership(student, course)
        val assignment = Assignment(
            id = 30,
            course = course,
            name = "Sorting",
            description = null,
            workspaceKey = "assignment-30",
            dirName = "assignment-30",
            starterDistributionPending = true,
            lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
            scheduleStatus = AssignmentScheduleStatus.OPEN,
            kickoffDate = LocalDateTime.now().minusDays(1),
            deadlineDate = LocalDateTime.now().plusDays(1)
        )

        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(userRepository.findByEmail(student.email)).thenReturn(student)
        `when`(membershipRepository.findByUserIdAndCourseIdForUpdate(student.id, course.id)).thenReturn(membership)
        `when`(assignmentRepository.findByIdAndCourseIdForUpdate(assignment.id, course.id))
            .thenReturn(Optional.of(assignment))

        val error = assertThrows<PublicApiException> {
            service.ensureInspector(admin.email, student.email, course.id, assignment.id)
        }

        assertEquals("STARTER_DISTRIBUTION_PENDING", error.errorCode)
        verify(jcodeRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any())
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

    private fun membership(user: User, course: Course) = UserCourses(
        id = 20,
        user = user,
        course = course,
        role = RoleType.STUDENT,
        lifecycleStatus = MembershipStatus.READY
    )
}
