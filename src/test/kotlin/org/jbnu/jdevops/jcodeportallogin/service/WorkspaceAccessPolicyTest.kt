package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDateTime

class WorkspaceAccessPolicyTest {
    private val assignments = mock(AssignmentRepository::class.java)
    private val policy = WorkspaceAccessPolicy(assignments)
    private val now = LocalDateTime.of(2026, 9, 13, 12, 0)

    @Test
    fun `student sees only active assignments in half-open schedule window`() {
        val jcode = jcode(RoleType.STUDENT)
        val open = assignment(jcode.course, 1, now.minusHours(1), now.plusHours(1))
        val future = assignment(jcode.course, 2, now.plusSeconds(1), now.plusHours(2))
        val deadline = assignment(jcode.course, 3, now.minusHours(1), now)
        `when`(assignments.findByCourseId(jcode.course.id)).thenReturn(listOf(future, deadline, open))

        val plan = policy.plan(jcode, now)

        assertEquals(listOf("assignment-1"), plan.assignments.map { it.workspaceKey })
        assertEquals(64, plan.mountHash.length)
        assertEquals(open.deadlineDate, plan.validUntil)
    }

    @Test
    fun `course manager JCode never receives student assignment mounts`() {
        val jcode = jcode(RoleType.ASSISTANT)
        `when`(assignments.findByCourseId(jcode.course.id)).thenReturn(
            listOf(assignment(jcode.course, 1, now.minusHours(1), now.plusHours(1)))
        )

        assertEquals(emptyList<WorkspaceAssignmentMount>(), policy.plan(jcode, now).assignments)
    }

    @Test
    fun `renaming assignment changes descriptor fingerprint before redirect`() {
        val jcode = jcode(RoleType.STUDENT)
        val original = assignment(jcode.course, 1, now.minusHours(1), now.plusHours(1), "Sorting")
        `when`(assignments.findByCourseId(jcode.course.id)).thenReturn(listOf(original))
        val first = policy.plan(jcode, now).mountHash
        val renamed = assignment(jcode.course, 1, now.minusHours(1), now.plusHours(1), "Stable sorting")
        `when`(assignments.findByCourseId(jcode.course.id)).thenReturn(listOf(renamed))

        val renamedPlan = policy.plan(jcode, now)
        org.junit.jupiter.api.Assertions.assertNotEquals(first, renamedPlan.mountHash)
        assertEquals("Stable sorting", renamedPlan.assignments.single().displayName)
    }

    @Test
    fun `inspector keeps one active assignment outside the student schedule window`() {
        val base = jcode(RoleType.STUDENT)
        val assignment = assignment(base.course, 7, now.plusDays(1), now.plusDays(2))
        val inspector = Jcode(
            id = 41,
            userCourse = base.userCourse,
            course = base.course,
            user = base.user,
            assignment = assignment,
            kind = JcodeKind.INSPECTOR,
            desiredRevision = 7
        )

        assertEquals(listOf("assignment-7"), policy.plan(inspector, now).assignments.map { it.workspaceKey })
        assertEquals(null, policy.plan(inspector, now).validUntil)
    }

    @Test
    fun `assignment scoped student session expires at its assignment deadline`() {
        val base = jcode(RoleType.STUDENT)
        val course = base.course.copy(workspaceScope = WorkspaceScope.ASSIGNMENT)
        val membership = base.userCourse.copy(course = course)
        val assignment = assignment(course, 8, now.minusMinutes(1), now.plusMinutes(5))
        val jcode = Jcode(
            id = 42,
            userCourse = membership,
            course = course,
            user = base.user,
            assignment = assignment,
            desiredRevision = 7
        )

        val plan = policy.plan(jcode, now)

        assertEquals(listOf("assignment-8"), plan.assignments.map { it.workspaceKey })
        assertEquals(assignment.deadlineDate, plan.validUntil)
    }

    @Test
    fun `starter distribution hides assignment from students but not inspectors`() {
        val studentJcode = jcode(RoleType.STUDENT)
        val assignment = assignment(
            studentJcode.course,
            9,
            now.minusMinutes(5),
            now.plusMinutes(5)
        ).also { it.starterDistributionPending = true }
        `when`(assignments.findByCourseId(studentJcode.course.id)).thenReturn(listOf(assignment))
        val inspector = Jcode(
            id = 43,
            userCourse = studentJcode.userCourse,
            course = studentJcode.course,
            user = studentJcode.user,
            assignment = assignment,
            kind = JcodeKind.INSPECTOR,
            desiredRevision = 7
        )

        assertEquals(emptyList<WorkspaceAssignmentMount>(), policy.plan(studentJcode, now).assignments)
        assertEquals(listOf("assignment-9"), policy.plan(inspector, now).assignments.map { it.workspaceKey })
    }

    private fun jcode(role: RoleType): Jcode {
        val course = Course(
            id = 10, name = "Algorithms", infrastructureKey = "alg", year = 2026,
            term = 2, professor = "Professor", clss = 1, vnc = false,
            courseKey = "key", workspacePolicyRevision = 7
        )
        val user = User(id = 20, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 30, course = course, user = user, role = role, lifecycleStatus = MembershipStatus.READY
        )
        return Jcode(
            id = 40, userCourse = membership, course = course, user = user,
            desiredRevision = course.workspacePolicyRevision
        )
    }

    private fun assignment(
        course: Course,
        id: Long,
        kickoff: LocalDateTime,
        deadline: LocalDateTime,
        name: String = "Assignment $id"
    ) = Assignment(
        id = id,
        course = course,
        name = name,
        description = null,
        workspaceKey = "assignment-$id",
        dirName = "assignment-$id",
        lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
        scheduleStatus = AssignmentScheduleStatus.OPEN,
        kickoffDate = kickoff,
        deadlineDate = deadline
    )
}
