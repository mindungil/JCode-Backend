package org.jbnu.jdevops.jcodeportallogin.repo

import org.jbnu.jdevops.jcodeportallogin.entity.Jcode
import org.jbnu.jdevops.jcodeportallogin.entity.User
import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.UserCourses
import org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.JcodeKind
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.util.Optional

interface JCodeRepository : JpaRepository<Jcode, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Jcode j where j.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<Jcode>

    // A locking read observes the latest commit even under REPEATABLE READ.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Jcode j where j.instanceKey = :instanceKey and j.lifecycleStatus <> org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus.ARCHIVED")
    fun findActiveInstanceForUpdate(instanceKey: String): Jcode?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Jcode j where j.user.id = :userId and j.course.id = :courseId and j.assignment.id = :assignmentId and j.kind = org.jbnu.jdevops.jcodeportallogin.entity.JcodeKind.INSPECTOR and j.lifecycleStatus <> org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus.ARCHIVED order by j.id desc")
    fun findInspectorsForUpdate(userId: Long, courseId: Long, assignmentId: Long): List<Jcode>

    fun findByUserId(userId: Long): List<Jcode>
    fun findByUserCourse(userCourse: UserCourses): Jcode?
    fun findAllByUserCourse(userCourse: UserCourses): List<Jcode>
    fun findFirstByUserIdAndCourseIdAndSnapshotAndAssignmentIdAndLifecycleStatusNotOrderByIdDesc(
        userId: Long, courseId: Long, snapshot: Boolean, assignmentId: Long, excludedStatus: JcodeLifecycleStatus
    ): Jcode?
    fun findFirstByUserIdAndCourseIdAndAssignmentIdAndKindAndLifecycleStatusNotOrderByIdDesc(
        userId: Long,
        courseId: Long,
        assignmentId: Long,
        kind: JcodeKind,
        excludedStatus: JcodeLifecycleStatus
    ): Jcode?
    fun findFirstByUserIdAndCourseIdAndSnapshotAndAssignmentIsNullAndLifecycleStatusNotOrderByIdDesc(
        userId: Long, courseId: Long, snapshot: Boolean, excludedStatus: JcodeLifecycleStatus
    ): Jcode?
    fun findByCourseId(courseId: Long): List<Jcode>
    fun findByAssignmentId(assignmentId: Long): List<Jcode>
    fun findByKindAndExpiresAtBeforeAndLifecycleStatusNot(
        kind: JcodeKind,
        expiresAt: java.time.LocalDateTime,
        excludedStatus: JcodeLifecycleStatus
    ): List<Jcode>
}
