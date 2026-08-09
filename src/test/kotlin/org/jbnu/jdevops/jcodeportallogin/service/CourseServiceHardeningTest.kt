package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.dto.course.CourseDto
import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.RoleType
import org.jbnu.jdevops.jcodeportallogin.entity.User
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserCoursesRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserRepository
import org.jbnu.jdevops.jcodeportallogin.util.CourseKeyUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class CourseServiceHardeningTest {
    private val userCoursesRepository = mock(UserCoursesRepository::class.java)
    private val assignmentRepository = mock(AssignmentRepository::class.java)
    private val courseRepository = mock(CourseRepository::class.java)
    private val jCodeRepository = mock(JCodeRepository::class.java)
    private val courseKeyUtil = mock(CourseKeyUtil::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val generatorWebClient = WebClient.builder().build()
    private val service = CourseService(
        userCoursesRepository,
        assignmentRepository,
        courseRepository,
        jCodeRepository,
        courseKeyUtil,
        passwordEncoder,
        userRepository,
        generatorWebClient,
    )

    @Test
    fun `course infrastructure identifiers cannot be edited`() {
        val course = course()
        val admin = User(id = 1, email = "admin@example.com", role = RoleType.ADMIN, studentNum = 1)
        val request = dto(code = "NEWCODE", clss = course.clss, vnc = course.vnc)
        `when`(userRepository.findByEmail(admin.email)).thenReturn(admin)
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))

        val ex = assertThrows<ResponseStatusException> {
            service.updateCourse(course.id, request, admin.email)
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(courseRepository, never()).save(org.mockito.ArgumentMatchers.any(Course::class.java))
    }

    private fun course() = Course(
        id = 10,
        name = "Algorithms",
        code = "ALG",
        year = 2026,
        term = 1,
        professor = "Professor",
        clss = 1,
        vnc = false,
        courseKey = "course-key",
    )

    private fun dto(code: String, clss: Int, vnc: Boolean) = CourseDto(
        courseId = 10,
        name = "Algorithms",
        code = code,
        professor = "Professor",
        year = 2026,
        term = 1,
        clss = clss,
        vnc = vnc,
    )
}
