package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.entity.RoleType
import org.jbnu.jdevops.jcodeportallogin.entity.User
import org.jbnu.jdevops.jcodeportallogin.entity.UserCourses
import org.jbnu.jdevops.jcodeportallogin.entity.MembershipStatus
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationAction
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceOperationTarget
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.LoginRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserCoursesRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.any
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

@Suppress("UNCHECKED_CAST")
class UserServiceRbacTest {
    private val userRepository = mock(UserRepository::class.java)
    private val loginRepository = mock(LoginRepository::class.java)
    private val jcodeRepository = mock(JCodeRepository::class.java)
    private val userCoursesRepository = mock(UserCoursesRepository::class.java)
    private val assignmentRepository = mock(AssignmentRepository::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val courseRepository = mock(CourseRepository::class.java)
    private val redisTemplate = mock(StringRedisTemplate::class.java)
    private val setOperations = mock(SetOperations::class.java) as SetOperations<String, String>
    private val redisService = RedisService(redisTemplate)
    private val jCodeService = mock(JCodeService::class.java)
    private val workspaceOperationStore = mock(WorkspaceOperationStore::class.java)
    private val workspacePolicyRevisionService = mock(WorkspacePolicyRevisionService::class.java)

    private val service = UserService(
        userRepository,
        loginRepository,
        jcodeRepository,
        userCoursesRepository,
        assignmentRepository,
        passwordEncoder,
        courseRepository,
        redisService,
        jCodeService,
        workspaceOperationStore,
        workspacePolicyRevisionService
    )

    init {
        `when`(redisTemplate.opsForSet()).thenReturn(setOperations)
    }

    @Test
    fun `professor cannot chase out student from unmanaged course`() {
        val course = course()
        val professor = user(1, "professor@example.com", RoleType.PROFESSOR)
        val student = user(2, "student@example.com", RoleType.STUDENT)
        val targetUserCourse = userCourse(student, course, RoleType.STUDENT)

        `when`(userRepository.findByEmail(professor.email)).thenReturn(professor)
        `when`(userRepository.findById(student.id)).thenReturn(student)
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(userCoursesRepository.findByUserIdAndCourseId(professor.id, course.id)).thenReturn(null)
        `when`(userCoursesRepository.findByUserIdAndCourseId(student.id, course.id)).thenReturn(targetUserCourse)

        val ex = assertThrows<ResponseStatusException> {
            service.chaseOutCourse(student.id, course.id, professor.email, "token")
        }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(userCoursesRepository, never()).delete(any(UserCourses::class.java))
    }

    @Test
    fun `course professor can chase out enrolled student from managed course`() {
        val course = course()
        val professor = user(1, "professor@example.com", RoleType.PROFESSOR)
        val student = user(2, "student@example.com", RoleType.STUDENT)
        val professorCourse = userCourse(professor, course, RoleType.PROFESSOR).also {
            it.lifecycleStatus = MembershipStatus.READY
        }
        val targetUserCourse = userCourse(student, course, RoleType.STUDENT)

        `when`(userRepository.findByEmail(professor.email)).thenReturn(professor)
        `when`(userRepository.findById(student.id)).thenReturn(student)
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))
        `when`(userCoursesRepository.findByUserIdAndCourseId(professor.id, course.id)).thenReturn(professorCourse)
        `when`(userCoursesRepository.findByUserIdAndCourseIdForUpdate(student.id, course.id)).thenReturn(targetUserCourse)
        val result = service.chaseOutCourse(student.id, course.id, professor.email, "token")

        assertEquals(course.id, result)
        assertEquals(MembershipStatus.DELETE_PENDING, targetUserCourse.lifecycleStatus)
        verify(userCoursesRepository).save(targetUserCourse)
        verify(workspaceOperationStore).enqueue(
            WorkspaceOperationTarget.MEMBERSHIP,
            targetUserCourse.id,
            WorkspaceOperationAction.DELETE_MEMBERSHIP
        )
        verify(redisTemplate).delete("user:${student.email}:course:${course.infrastructureKey}:${course.clss}")
        verify(redisTemplate).delete("user:${student.email}:course:${course.infrastructureKey}:${course.clss}:snapshot")
        verify(setOperations).remove("course:${course.infrastructureKey}:${course.clss}:managers", student.email)
    }

    @Test
    fun `user cannot join ended course`() {
        val course = course().apply { status = CourseStatus.ENDED }
        val user = user(2, "student@example.com", RoleType.STUDENT)
        val rawKey = "ALG-1-secret"

        `when`(courseRepository.findByInfrastructureKeyAndClss(course.infrastructureKey, course.clss)).thenReturn(listOf(course))
        `when`(passwordEncoder.matches(rawKey, course.courseKey)).thenReturn(true)
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(userRepository.findByEmail(user.email)).thenReturn(user)

        val ex = assertThrows<ResponseStatusException> {
            service.joinCourse(user.email, rawKey)
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(userCoursesRepository, never()).save(any(UserCourses::class.java))
    }

    @Test
    fun `course key always enrolls user as course student`() {
        val course = course()
        val professor = user(2, "professor@example.com", RoleType.PROFESSOR)
        val rawKey = "ALG-1-secret"

        `when`(courseRepository.findByInfrastructureKeyAndClss(course.infrastructureKey, course.clss)).thenReturn(listOf(course))
        `when`(passwordEncoder.matches(rawKey, course.courseKey)).thenReturn(true)
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(userRepository.findByEmail(professor.email)).thenReturn(professor)
        `when`(userCoursesRepository.findByUserIdAndCourseId(professor.id, course.id)).thenReturn(null)

        service.joinCourse(professor.email, rawKey)

        val captor = ArgumentCaptor.forClass(UserCourses::class.java)
        verify(userCoursesRepository).saveAndFlush(captor.capture())
        assertEquals(RoleType.STUDENT, captor.value.role)
        assertEquals(MembershipStatus.PROVISIONING, captor.value.lifecycleStatus)
        verify(workspaceOperationStore).enqueue(
            WorkspaceOperationTarget.MEMBERSHIP,
            captor.value.id,
            WorkspaceOperationAction.PROVISION_MEMBERSHIP
        )
    }

    @Test
    fun `global role update does not overwrite course memberships`() {
        val admin = user(1, "admin@example.com", RoleType.ADMIN)
        val target = user(2, "target@example.com", RoleType.STUDENT)
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(userRepository.findById(target.id)).thenReturn(target)

        service.updateUserRole(admin.email, target.id, RoleType.PROFESSOR, null)

        assertEquals(RoleType.PROFESSOR, target.role)
        verify(userRepository).save(target)
        verify(userCoursesRepository, never()).save(any(UserCourses::class.java))
    }

    @Test
    fun `course promotion expires student-only sessions and refreshes workspace policy`() {
        val course = course()
        val professor = user(1, "professor@example.com", RoleType.STUDENT)
        val target = user(2, "student@example.com", RoleType.STUDENT)
        val professorMembership = userCourse(professor, course, RoleType.PROFESSOR).also {
            it.lifecycleStatus = MembershipStatus.READY
        }
        val targetMembership = userCourse(target, course, RoleType.STUDENT).also {
            it.lifecycleStatus = MembershipStatus.READY
        }
        `when`(userRepository.findByEmail(professor.email)).thenReturn(professor)
        `when`(userRepository.findById(target.id)).thenReturn(target)
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(userCoursesRepository.findByUserIdAndCourseId(professor.id, course.id))
            .thenReturn(professorMembership)
        `when`(userCoursesRepository.findByUserIdAndCourseIdForUpdate(target.id, course.id))
            .thenReturn(targetMembership)

        service.updateUserRole(professor.email, target.id, RoleType.ASSISTANT, course.id)

        assertEquals(RoleType.ASSISTANT, targetMembership.role)
        verify(jCodeService).expireStudentOnlySessions(targetMembership)
        verify(workspacePolicyRevisionService).bump(course.id)
    }

    @Test
    fun `course manager demotion prepares student workspace before exposing membership`() {
        val course = course()
        val admin = user(1, "admin@example.com", RoleType.ADMIN)
        val target = user(2, "professor@example.com", RoleType.PROFESSOR)
        val targetMembership = userCourse(target, course, RoleType.PROFESSOR).also {
            it.lifecycleStatus = MembershipStatus.READY
        }
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(userRepository.findById(target.id)).thenReturn(target)
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(userCoursesRepository.findByUserIdAndCourseIdForUpdate(target.id, course.id))
            .thenReturn(targetMembership)

        service.updateUserRole(admin.email, target.id, RoleType.STUDENT, course.id)

        assertEquals(RoleType.STUDENT, targetMembership.role)
        assertEquals(MembershipStatus.PROVISIONING, targetMembership.lifecycleStatus)
        verify(jCodeService).expireManagerOnlySessions(targetMembership)
        verify(workspaceOperationStore).enqueue(
            WorkspaceOperationTarget.MEMBERSHIP,
            targetMembership.id,
            WorkspaceOperationAction.PROVISION_MEMBERSHIP
        )
        verify(workspacePolicyRevisionService).bump(course.id)
    }

    @Test
    fun `student without workspace identifier cannot join course`() {
        val course = course()
        val student = User(id = 2, email = "student@example.com", role = RoleType.STUDENT, studentNum = null)
        val rawKey = "ALG-1-secret"
        `when`(courseRepository.findByInfrastructureKeyAndClss(course.infrastructureKey, course.clss)).thenReturn(listOf(course))
        `when`(passwordEncoder.matches(rawKey, course.courseKey)).thenReturn(true)
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(userRepository.findByEmail(student.email)).thenReturn(student)

        val error = assertThrows<PublicApiException> {
            service.joinCourse(student.email, rawKey)
        }

        assertEquals(HttpStatus.CONFLICT, error.status)
        verify(userCoursesRepository, never()).saveAndFlush(any(UserCourses::class.java))
    }

    @Test
    fun `manager without workspace identifier cannot be demoted to student`() {
        val course = course()
        val admin = user(1, "admin@example.com", RoleType.ADMIN)
        val target = User(id = 2, email = "professor@example.com", role = RoleType.PROFESSOR, studentNum = null)
        val membership = userCourse(target, course, RoleType.PROFESSOR).also {
            it.lifecycleStatus = MembershipStatus.READY
        }
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(userRepository.findById(target.id)).thenReturn(target)
        `when`(courseRepository.findByIdForUpdate(course.id)).thenReturn(Optional.of(course))
        `when`(userCoursesRepository.findByUserIdAndCourseIdForUpdate(target.id, course.id)).thenReturn(membership)

        val error = assertThrows<PublicApiException> {
            service.updateUserRole(admin.email, target.id, RoleType.STUDENT, course.id)
        }

        assertEquals(HttpStatus.CONFLICT, error.status)
        assertEquals(RoleType.PROFESSOR, membership.role)
        verify(userCoursesRepository, never()).save(membership)
    }

    @Test
    fun `user with course history cannot be deleted directly`() {
        val target = user(2, "student@example.com", RoleType.STUDENT)
        `when`(userRepository.findById(target.id)).thenReturn(target)
        `when`(userCoursesRepository.findByUserId(target.id)).thenReturn(listOf(userCourse(target, course(), RoleType.STUDENT)))

        val error = assertThrows<PublicApiException> { service.deleteUser(target.id) }

        assertEquals(HttpStatus.CONFLICT, error.status)
        verify(userRepository, never()).delete(target)
    }

    @Test
    fun `admin role cannot be stored as a course membership role`() {
        val admin = user(1, "admin@example.com", RoleType.ADMIN)
        val target = user(2, "student@example.com", RoleType.STUDENT)
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(userRepository.findById(target.id)).thenReturn(target)

        val error = assertThrows<ResponseStatusException> {
            service.updateUserRole(admin.email, target.id, RoleType.ADMIN, course().id)
        }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        verify(userCoursesRepository, never()).save(any(UserCourses::class.java))
    }

    @Test
    fun `user course list hides archived memberships and ended courses`() {
        val activeCourse = course()
        val endedCourse = course().copy(id = 11).apply { status = CourseStatus.ENDED }
        val archivedCourse = course().copy(id = 12).apply { status = CourseStatus.ARCHIVED }
        val baseUser = user(2, "student@example.com", RoleType.STUDENT)
        val visible = userCourse(baseUser, activeCourse, RoleType.STUDENT).apply {
            lifecycleStatus = MembershipStatus.READY
        }
        val left = userCourse(baseUser, activeCourse.copy(id = 13), RoleType.STUDENT).apply {
            lifecycleStatus = MembershipStatus.ARCHIVED
        }
        val ended = userCourse(baseUser, endedCourse, RoleType.STUDENT).apply {
            lifecycleStatus = MembershipStatus.READY
        }
        val archived = userCourse(baseUser, archivedCourse, RoleType.STUDENT).apply {
            lifecycleStatus = MembershipStatus.READY
        }
        val user = baseUser.copy(courses = listOf(visible, left, ended, archived))
        `when`(userRepository.findByEmail(user.email)).thenReturn(user)

        val result = service.getUserCourses(user.email)

        assertEquals(listOf(activeCourse.id), result.map { it.courseId })
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
        courseKey = "course-key"
    )

    private fun user(id: Long, email: String, role: RoleType) = User(
        id = id,
        email = email,
        role = role,
        studentNum = id.toInt()
    )

    private fun userCourse(user: User, course: Course, role: RoleType) = UserCourses(
        id = user.id,
        user = user,
        course = course,
        role = role
    )
}
