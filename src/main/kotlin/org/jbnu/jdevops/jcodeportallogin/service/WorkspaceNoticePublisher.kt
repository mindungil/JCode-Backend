package org.jbnu.jdevops.jcodeportallogin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.AssignmentScheduleStatus
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceScope
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Publishes upcoming kickoff/deadline transitions for the IDE warning toast.
 *
 * Every schedule transition bumps the course policy revision, which drops the
 * router routes of all open IDE sessions in that course and restarts workspaces
 * whose mounts change. The Router streams this list to open IDE pages, which
 * time the warnings locally. Every replica writes the same value; the TTL clears
 * it if publishing stops.
 */
@Component
class WorkspaceNoticePublisher(
    private val assignmentRepository: AssignmentRepository,
    private val courseRepository: CourseRepository,
    private val redisTemplate: StringRedisTemplate,
    private val closedAssignmentAccess: ClosedAssignmentAccess,
    @Value("\${workspace.notices.enabled:false}") private val enabled: Boolean,
    @Value("\${workspace.notices.window-minutes:15}") private val windowMinutes: Long,
    @Value("\${workspace.notices.ttl-seconds:120}") private val ttlSeconds: Long,
    // Minutes before a transition at which open IDE pages warn; the last one stays
    // on screen until dismissed. Sent with every notice, so no image rebuild is needed.
    @Value("\${workspace.notices.deadline-lead-minutes:10,5,1}") deadlineLeadMinutes: String = "10,5,1",
    @Value("\${workspace.notices.kickoff-lead-minutes:1}") kickoffLeadMinutes: String = "1"
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()
    private val deadlineLeads = parseLeadMinutes(deadlineLeadMinutes)
    private val kickoffLeads = parseLeadMinutes(kickoffLeadMinutes)
    // The window must reach the earliest warning, or that warning could never be shown.
    private val window = maxOf(windowMinutes, (deadlineLeads + kickoffLeads).maxOrNull()?.toLong() ?: 0L)
        .coerceIn(1, 60)

    @Scheduled(
        fixedDelayString = "\${workspace.notices.publish-delay-ms:15000}",
        initialDelayString = "\${workspace.notices.initial-delay-ms:10000}"
    )
    @Transactional(readOnly = true)
    fun publish() {
        if (!enabled) return
        try {
            publish(LocalDateTime.now())
        } catch (error: Exception) {
            logger.warn("IDE notice publishing failed: {}", error.message)
        }
    }

    fun publish(now: LocalDateTime) {
        val until = now.plusMinutes(window)
        val upcoming = assignmentRepository.findUpcomingScheduleTransitions(
            CourseStatus.ACTIVE,
            AssignmentLifecycleStatus.ACTIVE,
            AssignmentScheduleStatus.SCHEDULED,
            setOf(AssignmentScheduleStatus.SCHEDULED, AssignmentScheduleStatus.OPEN),
            now,
            until
        ).groupBy { it.course.id }
        // Courses without upcoming transitions get an empty list, so a moved
        // deadline cancels toasts already scheduled in open IDE pages.
        courseRepository.findByStatusAndWorkspaceRuntimeEnabledTrue(CourseStatus.ACTIVE).forEach { course ->
            val transitions = upcoming[course.id].orEmpty().flatMap { assignment ->
                buildList {
                    if (assignment.scheduleStatus == AssignmentScheduleStatus.SCHEDULED &&
                        assignment.kickoffDate.isAfter(now) &&
                        !assignment.kickoffDate.isAfter(until)
                    ) add(transition(assignment.id, assignment.name, "KICKOFF", assignment.kickoffDate, false, kickoffLeads))
                    if (assignment.deadlineDate.isAfter(now) &&
                        !assignment.deadlineDate.isAfter(until)
                    ) add(
                        transition(
                            assignment.id,
                            assignment.name,
                            "DEADLINE",
                            assignment.deadlineDate,
                            course.workspaceScope == WorkspaceScope.COURSE &&
                                closedAssignmentAccess.readOnlyEnabledForDisplay(assignment.deadlineDate),
                            deadlineLeads
                        )
                    )
                }
            }.sortedBy { it["at"] as Long }
            redisTemplate.opsForValue().set(
                key(course.infrastructureKey, course.clss),
                objectMapper.writeValueAsString(mapOf("version" to 1, "transitions" to transitions)),
                Duration.ofSeconds(ttlSeconds.coerceAtLeast(30))
            )
        }
    }

    private fun transition(
        assignmentId: Long,
        assignmentName: String,
        kind: String,
        at: LocalDateTime,
        readOnlyAfter: Boolean,
        leadMinutes: List<Int>
    ): Map<String, Any> {
        val epochMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return mapOf(
            // A changed time is a new notice, so the IDE does not suppress it as already shown.
            "id" to "$assignmentId:$kind:$epochMillis",
            "assignmentId" to assignmentId,
            "assignmentName" to assignmentName,
            "kind" to kind,
            "at" to epochMillis,
            "readOnlyAfter" to readOnlyAfter,
            "leadMinutes" to leadMinutes
        )
    }

    companion object {
        // The router reads the same key from the launch profile's courseCode/clss.
        fun key(infrastructureKey: String, clss: Int) = "jcode:notices:course:$infrastructureKey:$clss"

        internal fun parseLeadMinutes(value: String): List<Int> {
            val leads = value.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..60 }
                .distinct()
                .sortedDescending()
                .take(5)
            require(leads.isNotEmpty()) { "IDE notice lead minutes must contain 1..60: '$value'" }
            return leads
        }
    }
}
