package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.util.WorkspaceNaming
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

data class WorkspaceAssignmentMount(
    val assignmentId: Long,
    val workspaceKey: String,
    val displayName: String,
    // Closed assignments stay visible to their students but are mounted read-only.
    val readOnly: Boolean = false
)

enum class StudentAssignmentAccess { WRITE, READ_ONLY, NONE }

data class WorkspaceAccessPlan(
    val revision: Long,
    val mountHash: String,
    val assignments: List<WorkspaceAssignmentMount>,
    val validUntil: LocalDateTime?
)

@Service
class WorkspaceAccessPolicy(
    private val assignmentRepository: AssignmentRepository,
    private val closedAssignmentAccess: ClosedAssignmentAccess
) {
    fun plan(jcode: Jcode, now: LocalDateTime = LocalDateTime.now()): WorkspaceAccessPlan {
        val selectedAssignments: List<Pair<Assignment, Boolean>> = if (jcode.kind == JcodeKind.INSPECTOR) {
            jcode.assignment
                ?.takeIf { it.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE }
                ?.let { listOf(it to false) }
                ?: emptyList()
        } else if (eligibleAssignmentWorkspace(jcode)) {
            jcode.assignment
                ?.takeIf { isStudentAccessible(it, now) }
                ?.let { listOf(it to false) }
                ?: emptyList()
        } else if (eligibleStudentWorkspace(jcode)) {
            val courseAssignments = assignmentRepository.findByCourseId(jcode.course.id)
            val writable = courseAssignments.filter { isStudentAccessible(it, now) }
            // Ask about read-only access only when a closed assignment exists, so courses
            // without one never depend on the Generator capability check.
            val closed = courseAssignments.filter { isClosedForStudent(it, jcode.userCourse, now) }
            val readOnly = if (closed.isNotEmpty() && closedAssignmentAccess.readOnlyEnabled(now)) closed else emptyList()
            (writable.map { it to false } + readOnly.map { it to true })
                .sortedBy { it.first.id }
        } else {
            emptyList()
        }
        val assignments = selectedAssignments.map { (assignment, readOnly) ->
            WorkspaceAssignmentMount(assignment.id, assignment.workspaceKey, assignment.name, readOnly)
        }
        return WorkspaceAccessPlan(
            revision = jcode.desiredRevision,
            mountHash = hash(jcode, assignments),
            assignments = assignments,
            validUntil = if (jcode.kind == JcodeKind.INSPECTOR) {
                null
            } else {
                // Read-only mounts never end a session; only writable work has a deadline.
                selectedAssignments.filterNot { it.second }.minOfOrNull { it.first.deadlineDate }
            }
        )
    }

    /**
     * Portal hint for a student's assignment list; agrees with [isStudentLaunchable].
     * The list must still load while the Generator is unreachable.
     */
    fun studentAccess(
        assignments: List<Assignment>,
        membership: UserCourses,
        now: LocalDateTime = LocalDateTime.now()
    ): Map<Long, StudentAssignmentAccess> {
        val readOnlyEnabled by lazy { closedAssignmentAccess.readOnlyEnabledForDisplay(now) }
        return assignments.associate {
            it.id to when {
                isStudentAccessible(it, now) -> StudentAssignmentAccess.WRITE
                readOnlyCandidate(it, membership, now) && readOnlyEnabled -> StudentAssignmentAccess.READ_ONLY
                else -> StudentAssignmentAccess.NONE
            }
        }
    }

    /** Whether a student may open this assignment from the portal, writable or read-only. */
    fun isStudentLaunchable(
        assignment: Assignment,
        membership: UserCourses,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean = isStudentAccessible(assignment, now) || isStudentReadOnlyAccessible(assignment, membership, now)

    fun isStudentReadOnlyAccessible(
        assignment: Assignment,
        membership: UserCourses,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean = readOnlyCandidate(assignment, membership, now) && closedAssignmentAccess.readOnlyEnabled(now)

    private fun readOnlyCandidate(assignment: Assignment, membership: UserCourses, now: LocalDateTime): Boolean =
        assignment.course.workspaceScope == WorkspaceScope.COURSE &&
            membership.role == RoleType.STUDENT &&
            isClosedForStudent(assignment, membership, now)

    private fun isClosedForStudent(assignment: Assignment, membership: UserCourses, now: LocalDateTime): Boolean {
        if (assignment.course.status != CourseStatus.ACTIVE) return false
        val closed = when (assignment.lifecycleStatus) {
            AssignmentLifecycleStatus.ACTIVE -> !now.isBefore(assignment.deadlineDate)
            // A reopen keeps the finalized work visible until the restore succeeds, so
            // the mount set does not flap while RESTORE_ASSIGNMENT is pending or retried.
            AssignmentLifecycleStatus.PROVISIONING,
            AssignmentLifecycleStatus.PROVISION_FAILED -> assignment.finalizedAt != null
            else -> false
        }
        if (!closed) return false
        // One closing time for the whole close/finalize/reopen cycle: finalization never
        // precedes the deadline, and a pending reopen has already moved the deadline.
        val closedAt = assignment.finalizedAt?.let { minOf(it, assignment.deadlineDate) } ?: assignment.deadlineDate
        // Members who joined later never had this assignment directory; the Generator
        // still mounts an empty placeholder for anyone whose directory is missing.
        return membership.createdAt.isBefore(closedAt)
    }

    fun isStudentAccessible(assignment: Assignment, now: LocalDateTime = LocalDateTime.now()): Boolean =
            assignment.course.status == CourseStatus.ACTIVE &&
            assignment.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
            !assignment.starterDistributionPending &&
            !now.isBefore(assignment.kickoffDate) &&
            now.isBefore(assignment.deadlineDate)

    private fun eligibleStudentWorkspace(jcode: Jcode): Boolean =
        !jcode.snapshot &&
            jcode.assignment == null &&
            jcode.course.workspaceScope == WorkspaceScope.COURSE &&
            jcode.course.status == CourseStatus.ACTIVE &&
            jcode.userCourse.lifecycleStatus == MembershipStatus.READY &&
            jcode.userCourse.role == RoleType.STUDENT

    private fun eligibleAssignmentWorkspace(jcode: Jcode): Boolean =
        !jcode.snapshot &&
            jcode.kind == JcodeKind.STANDARD &&
            jcode.assignment != null &&
            jcode.course.workspaceScope == WorkspaceScope.ASSIGNMENT &&
            jcode.userCourse.lifecycleStatus == MembershipStatus.READY &&
            jcode.userCourse.role == RoleType.STUDENT

    private fun hash(jcode: Jcode, assignments: List<WorkspaceAssignmentMount>): String {
        // Assignment labels are updated in place and must not invalidate an access grant.
        val canonical = buildString {
            append(WorkspaceNaming.displayName(jcode.user))
            assignments.forEach {
                append('\n')
                append(it.assignmentId)
                append('\u0000')
                append(it.workspaceKey)
                // Writable entries keep their historical encoding, so enabling read-only
                // access restarts only workspaces whose mounts actually change.
                if (it.readOnly) append("\u0000ro")
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
