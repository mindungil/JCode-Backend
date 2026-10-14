package org.jbnu.jdevops.jcodeportallogin.repo

import org.jbnu.jdevops.jcodeportallogin.entity.Assignment
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentScheduleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// Runs the notice queries against the real Hibernate mapping (H2 in MySQL mode).
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkspaceNoticeQueryIntegrationTest {
    @Autowired
    private lateinit var courses: CourseRepository

    @Autowired
    private lateinit var assignments: AssignmentRepository

    private val now = LocalDateTime.of(2026, 10, 14, 23, 50)

    @Test
    fun `upcoming transitions cover kickoffs and deadlines of active runtime courses only`() {
        val course = course("noticeactive", runtime = true)
        val idle = course("noticeidle", runtime = false)
        val ended = course("noticeended", runtime = true, status = CourseStatus.ENDED)
        val kickoff = assignment(course, "kickoff", now.plusMinutes(5), now.plusDays(7), AssignmentScheduleStatus.SCHEDULED)
        val deadline = assignment(course, "deadline", now.minusDays(3), now.plusMinutes(9), AssignmentScheduleStatus.OPEN)
        assignment(course, "later", now.minusDays(3), now.plusHours(2), AssignmentScheduleStatus.OPEN)
        assignment(course, "closed", now.minusDays(3), now.minusMinutes(1), AssignmentScheduleStatus.CLOSED)
        assignment(course, "deleting", now.minusDays(3), now.plusMinutes(3), AssignmentScheduleStatus.OPEN,
            AssignmentLifecycleStatus.DELETING)
        assignment(idle, "idle", now.minusDays(3), now.plusMinutes(3), AssignmentScheduleStatus.OPEN)
        assignment(ended, "ended", now.minusDays(3), now.plusMinutes(3), AssignmentScheduleStatus.OPEN)

        val upcoming = assignments.findUpcomingScheduleTransitions(
            CourseStatus.ACTIVE,
            AssignmentLifecycleStatus.ACTIVE,
            AssignmentScheduleStatus.SCHEDULED,
            setOf(AssignmentScheduleStatus.SCHEDULED, AssignmentScheduleStatus.OPEN),
            now,
            now.plusMinutes(15)
        )

        assertEquals(listOf(kickoff.id, deadline.id).sorted(), upcoming.map { it.id })
        assertEquals(
            listOf(course.id),
            courses.findByStatusAndWorkspaceRuntimeEnabledTrue(CourseStatus.ACTIVE)
                .map { it.id }
                .filter { it in setOf(course.id, idle.id, ended.id) }
        )
    }

    private fun course(key: String, runtime: Boolean, status: CourseStatus = CourseStatus.ACTIVE) = courses.saveAndFlush(
        Course(
            name = key, infrastructureKey = key, year = 2026, term = 2, professor = "Professor",
            clss = 1, vnc = false, courseKey = "key-$key", workspaceRuntimeEnabled = runtime
        ).also { it.status = status }
    )

    private fun assignment(
        course: Course,
        name: String,
        kickoff: LocalDateTime,
        deadline: LocalDateTime,
        schedule: AssignmentScheduleStatus,
        lifecycle: AssignmentLifecycleStatus = AssignmentLifecycleStatus.ACTIVE
    ) = assignments.saveAndFlush(
        Assignment(
            course = course,
            name = name,
            description = null,
            lifecycleStatus = lifecycle,
            scheduleStatus = schedule,
            kickoffDate = kickoff,
            deadlineDate = deadline
        )
    )
}
