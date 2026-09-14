package org.jbnu.jdevops.jcodeportallogin.repo

import org.jbnu.jdevops.jcodeportallogin.entity.Assignment
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentPathBackfillStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentScheduleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.entity.StarterArtifactStatus
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.time.LocalDateTime
import java.util.Optional

interface AssignmentRepository : JpaRepository<Assignment, Long> {
    fun findByCourseId(courseId: Long): List<Assignment>
    fun findByIdAndCourseId(id: Long, courseId: Long): Optional<Assignment>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select assignment from Assignment assignment where assignment.id = :id and assignment.course.id = :courseId")
    fun findByIdAndCourseIdForUpdate(
        @Param("id") id: Long,
        @Param("courseId") courseId: Long
    ): Optional<Assignment>
    fun existsByCourseIdAndName(courseId: Long, assignmentName: String): Boolean
    fun existsByCourseIdAndNameAndIdNot(courseId: Long, assignmentName: String, assignmentId: Long): Boolean
    fun findByPathBackfillStatus(status: AssignmentPathBackfillStatus): List<Assignment>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select assignment from Assignment assignment
        where assignment.lifecycleStatus = :activeStatus
          and assignment.starterDistributionPending = true
          and assignment.hasStarterCode = false
          and assignment.lastError is null
          and (
            (assignment.createdAt <= :cutoff and not exists (
                select artifact.id from StarterArtifact artifact
                where artifact.assignment = assignment
            ))
            or exists (
                select artifact.id from StarterArtifact artifact
                where artifact.assignment = assignment
                  and artifact.status = :uploadingStatus
                  and artifact.uploadedAt <= :cutoff
            )
          )
        order by assignment.id
        """
    )
    fun findExpiredStarterUploadReservationsForUpdate(
        @Param("activeStatus") activeStatus: AssignmentLifecycleStatus,
        @Param("uploadingStatus") uploadingStatus: StarterArtifactStatus,
        @Param("cutoff") cutoff: LocalDateTime
    ): List<Assignment>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select assignment from Assignment assignment
        where assignment.course.status = :courseStatus
          and assignment.lifecycleStatus = :assignmentStatus
          and (
            (assignment.scheduleStatus = :scheduledStatus and assignment.kickoffDate <= :now)
            or (assignment.scheduleStatus = :openStatus and
                (assignment.kickoffDate > :now or assignment.deadlineDate <= :now))
            or (assignment.scheduleStatus = :closedStatus and assignment.deadlineDate <= :now and
                assignment.finalizedAt is null and assignment.finalizationGeneration = 0)
          )
        order by assignment.id
        """
    )
    fun findScheduleTransitionCandidatesForUpdate(
        @Param("courseStatus") courseStatus: CourseStatus,
        @Param("assignmentStatus") assignmentStatus: AssignmentLifecycleStatus,
        @Param("scheduledStatus") scheduledStatus: AssignmentScheduleStatus,
        @Param("openStatus") openStatus: AssignmentScheduleStatus,
        @Param("closedStatus") closedStatus: AssignmentScheduleStatus,
        @Param("now") now: LocalDateTime
    ): List<Assignment>
}
