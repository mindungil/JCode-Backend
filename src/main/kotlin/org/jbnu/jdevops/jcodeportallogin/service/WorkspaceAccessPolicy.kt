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
    val displayName: String
)

data class WorkspaceAccessPlan(
    val revision: Long,
    val mountHash: String,
    val assignments: List<WorkspaceAssignmentMount>,
    val validUntil: LocalDateTime?
)

@Service
class WorkspaceAccessPolicy(
    private val assignmentRepository: AssignmentRepository
) {
    fun plan(jcode: Jcode, now: LocalDateTime = LocalDateTime.now()): WorkspaceAccessPlan {
        val selectedAssignments = if (jcode.kind == JcodeKind.INSPECTOR) {
            jcode.assignment
                ?.takeIf { it.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE }
                ?.let(::listOf)
                ?: emptyList()
        } else if (eligibleAssignmentWorkspace(jcode)) {
            jcode.assignment
                ?.takeIf { isStudentAccessible(it, now) }
                ?.let(::listOf)
                ?: emptyList()
        } else if (eligibleStudentWorkspace(jcode)) {
            assignmentRepository.findByCourseId(jcode.course.id)
                .asSequence()
                .filter { isStudentAccessible(it, now) }
                .sortedBy { it.id }
                .toList()
        } else {
            emptyList()
        }
        val assignments = selectedAssignments.map {
            WorkspaceAssignmentMount(it.id, it.workspaceKey, it.name)
        }
        return WorkspaceAccessPlan(
            revision = jcode.desiredRevision,
            mountHash = hash(jcode, assignments),
            assignments = assignments,
            validUntil = if (jcode.kind == JcodeKind.INSPECTOR) {
                null
            } else {
                selectedAssignments.minOfOrNull { it.deadlineDate }
            }
        )
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
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
