package org.jbnu.jdevops.jcodeportallogin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional

class ClosedAssignmentReadOnlyTest {
    private val now = LocalDateTime.of(2026, 10, 14, 12, 0)
    private val mapper = jacksonObjectMapper()

    private class CountingAccess(private val enabled: Boolean) : ClosedAssignmentAccess {
        var calls = 0
        override fun readOnlyEnabled(now: LocalDateTime): Boolean {
            calls += 1
            return enabled
        }
    }

    @Test
    fun `closed assignment stays mounted read-only and does not end the session`() {
        val jcode = studentJcode()
        val closed = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))
        val open = assignment(jcode.course, 62, now.minusDays(1), now.plusDays(1))
        val future = assignment(jcode.course, 63, now.plusDays(7), now.plusDays(8))
        val policy = policy(listOf(future, open, closed), CountingAccess(true))

        val plan = policy.plan(jcode, now)

        assertEquals(
            listOf("assignment-60" to true, "assignment-62" to false),
            plan.assignments.map { it.workspaceKey to it.readOnly }
        )
        assertEquals(open.deadlineDate, plan.validUntil)
    }

    @Test
    fun `only read-only mounts leave the session expiry at its default`() {
        val jcode = studentJcode()
        val policy = policy(
            listOf(assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))),
            CountingAccess(true)
        )

        val plan = policy.plan(jcode, now)

        assertEquals(listOf(true), plan.assignments.map { it.readOnly })
        assertNull(plan.validUntil)
    }

    @Test
    fun `writable plans keep their historical hash`() {
        val jcode = studentJcode()
        val open = assignment(jcode.course, 62, now.minusDays(1), now.plusDays(1))
        val enabled = policy(listOf(open), CountingAccess(true)).plan(jcode, now)
        val legacy = policy(listOf(open), ClosedAssignmentAccess { false }).plan(jcode, now)

        assertEquals(legacy.mountHash, enabled.mountHash)
    }

    @Test
    fun `activation is an instant shared by every replica`() {
        val verifier = mock(GeneratorContractVerifier::class.java)
        `when`(verifier.supports(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenReturn(true)
        val access = ScheduledClosedAssignmentAccess(verifier, "2026-10-20T03:00:00", "")
        assertFalse(access.readOnlyEnabled(LocalDateTime.of(2026, 10, 20, 2, 59, 59)))
        assertTrue(access.readOnlyEnabled(LocalDateTime.of(2026, 10, 20, 3, 0)))
        val window = ScheduledClosedAssignmentAccess(verifier, "2026-10-20T03:00:00", "2026-10-21T03:00:00")
        assertFalse(window.readOnlyEnabled(LocalDateTime.of(2026, 10, 21, 3, 0)))
        assertFalse(ScheduledClosedAssignmentAccess(verifier, "", "").readOnlyEnabled(now))
    }

    @Test
    fun `an older Generator falls back to hiding closed assignments instead of blocking launches`() {
        val oldGenerator = mock(GeneratorContractVerifier::class.java)
        `when`(oldGenerator.supports(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenReturn(false)
        val access = ScheduledClosedAssignmentAccess(oldGenerator, "2026-10-01T00:00:00", "")
        assertFalse(access.readOnlyEnabled(now))

        val jcode = studentJcode()
        val closed = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))
        val open = assignment(jcode.course, 62, now.minusDays(1), now.plusDays(1))
        val plan = policy(listOf(closed, open), access).plan(jcode, now)
        assertEquals(listOf("assignment-62" to false), plan.assignments.map { it.workspaceKey to it.readOnly })
    }

    @Test
    fun `an unreachable Generator is reported like the existing launch check`() {
        val down = mock(GeneratorContractVerifier::class.java)
        `when`(down.supports(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenThrow(IllegalStateException("down"))
        val access = ScheduledClosedAssignmentAccess(down, "2026-10-01T00:00:00", "")
        val error = org.junit.jupiter.api.assertThrows<org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException> {
            access.readOnlyEnabled(now)
        }
        assertEquals("JCODE_RUNTIME_UNAVAILABLE", error.errorCode)

        // The portal list still loads; closed assignments are just not offered for now.
        val jcode = studentJcode()
        val closed = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))
        assertEquals(
            mapOf(60L to StudentAssignmentAccess.NONE),
            policy(listOf(closed), access).studentAccess(listOf(closed), jcode.userCourse, now)
        )
    }

    @Test
    fun `read-only mount changes the hash so the deadline restarts the workspace`() {
        val jcode = studentJcode()
        val beforeDeadline = assignment(jcode.course, 60, now.minusDays(14), now.plusMinutes(1))
        val writable = policy(listOf(beforeDeadline), CountingAccess(true)).plan(jcode, now)
        val readOnly = policy(listOf(beforeDeadline), CountingAccess(true)).plan(jcode, now.plusMinutes(2))

        assertEquals(listOf(false), writable.assignments.map { it.readOnly })
        assertEquals(listOf(true), readOnly.assignments.map { it.readOnly })
        assertNotEquals(writable.mountHash, readOnly.mountHash)
    }

    @Test
    fun `disabled feature keeps closed assignments unmounted`() {
        val jcode = studentJcode()
        val closed = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))

        assertEquals(emptyList<WorkspaceAssignmentMount>(), policy(listOf(closed), CountingAccess(false)).plan(jcode, now).assignments)
    }

    @Test
    fun `members who joined after the deadline get no closed assignment`() {
        val jcode = studentJcode(joinedAt = now.minusHours(1))
        val closed = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))
        assertEquals(emptyList<WorkspaceAssignmentMount>(), policy(listOf(closed), CountingAccess(true)).plan(jcode, now).assignments)
    }

    @Test
    fun `the closing time does not move when finalization completes`() {
        // Joined after the deadline but before finalization completed.
        val jcode = studentJcode(joinedAt = now.minusDays(1).plusSeconds(30))
        val closed = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))
        val policy = policy(listOf(closed), CountingAccess(true))
        assertEquals(emptyList<WorkspaceAssignmentMount>(), policy.plan(jcode, now).assignments)

        closed.finalizedAt = now.minusDays(1).plusMinutes(1)
        assertEquals(emptyList<WorkspaceAssignmentMount>(), policy.plan(jcode, now).assignments)
    }

    @Test
    fun `reopen keeps the finalized work read-only until the restore succeeds`() {
        val jcode = studentJcode()
        val restoring = assignment(jcode.course, 60, now.minusDays(14), now.plusDays(3)).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
            it.finalizedAt = now.minusDays(1)
        }
        val failedRestore = assignment(jcode.course, 61, now.minusDays(14), now.plusDays(3)).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISION_FAILED
            it.finalizedAt = now.minusDays(1)
        }
        val newAssignment = assignment(jcode.course, 64, now.minusDays(1), now.plusDays(3)).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.PROVISIONING
        }

        val plan = policy(listOf(restoring, failedRestore, newAssignment), CountingAccess(true)).plan(jcode, now)

        assertEquals(listOf("assignment-60" to true, "assignment-61" to true), plan.assignments.map { it.workspaceKey to it.readOnly })
        assertNull(plan.validUntil)
    }

    @Test
    fun `deleted archived and ended-course assignments are never mounted`() {
        val jcode = studentJcode()
        val deleting = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1)).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.DELETING
            it.finalizedAt = now.minusDays(1)
        }
        val archived = assignment(jcode.course, 61, now.minusDays(14), now.minusDays(1)).also {
            it.lifecycleStatus = AssignmentLifecycleStatus.ARCHIVED
            it.finalizedAt = now.minusDays(1)
        }
        val endedCourse = jcode.course.copy(status = CourseStatus.ENDED)
        val ended = assignment(endedCourse, 65, now.minusDays(14), now.minusDays(1))
        val policy = policy(listOf(deleting, archived), CountingAccess(true))

        assertEquals(emptyList<WorkspaceAssignmentMount>(), policy.plan(jcode, now).assignments)
        assertFalse(policy.isStudentLaunchable(ended, jcode.userCourse, now))
    }

    @Test
    fun `portal access hint and launch check agree`() {
        val jcode = studentJcode()
        val closed = assignment(jcode.course, 60, now.minusDays(14), now.minusDays(1))
        val open = assignment(jcode.course, 62, now.minusDays(1), now.plusDays(1))
        val future = assignment(jcode.course, 63, now.plusDays(7), now.plusDays(8))
        val policy = policy(listOf(closed, open, future), CountingAccess(true))

        assertEquals(
            mapOf(
                60L to StudentAssignmentAccess.READ_ONLY,
                62L to StudentAssignmentAccess.WRITE,
                63L to StudentAssignmentAccess.NONE
            ),
            policy.studentAccess(listOf(closed, open, future), jcode.userCourse, now)
        )
        assertTrue(policy.isStudentLaunchable(closed, jcode.userCourse, now))
        assertTrue(policy.isStudentLaunchable(open, jcode.userCourse, now))
        assertFalse(policy.isStudentLaunchable(future, jcode.userCourse, now))
    }

    @Test
    fun `assignment scoped courses keep closed assignments closed`() {
        val jcode = studentJcode()
        val assignmentCourse = jcode.course.copy(workspaceScope = WorkspaceScope.ASSIGNMENT)
        val assignmentScoped = assignment(assignmentCourse, 61, now.minusDays(14), now.minusDays(1))
        val membership = jcode.userCourse.copy(course = assignmentCourse)
        assertFalse(policy(emptyList(), CountingAccess(true)).isStudentLaunchable(assignmentScoped, membership, now))
    }

    @Test
    fun `reconciler sends closed assignments only as read-only mounts`() {
        val jcode = studentJcode().also { it.lifecycleStatus = JcodeLifecycleStatus.READY }
        val plan = WorkspaceAccessPlan(
            revision = jcode.desiredRevision,
            mountHash = "a".repeat(64),
            assignments = listOf(
                WorkspaceAssignmentMount(60, "assignment-60", "hw1", readOnly = true),
                WorkspaceAssignmentMount(62, "assignment-62", "hw2")
            ),
            validUntil = null
        )
        // plan(jcode) passes a fresh default `now`, so answer by method name.
        val accessPolicy = mock(WorkspaceAccessPolicy::class.java) { invocation ->
            if (invocation.method.name == "plan") plan else RETURNS_DEFAULTS.answer(invocation)
        }
        val operationStore = mock(WorkspaceOperationStore::class.java)
        val operation = operation(WorkspaceOperationTarget.JCODE, jcode.id, WorkspaceOperationAction.RECONCILE_JCODE_ACCESS, jcode.desiredRevision)
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)
        val bodies = mutableListOf<Map<*, *>>()

        reconciler(operationStore, accessPolicy, CountingAccess(true), bodies, """{"state":"READY"}""").reconcile()

        val payload = bodies.single()
        verify(operationStore).markSucceeded(operation, mapOf("policyRevision" to plan.revision, "mountHash" to plan.mountHash))
        assertEquals(listOf("assignment-62"), payload["assignment_dirs"])
        assertEquals(listOf("assignment-60"), payload["read_only_assignment_dirs"])
        assertEquals(mapOf("assignment-60" to "hw1", "assignment-62" to "hw2"), payload["assignment_labels"])
        assertEquals(false, payload["read_only_workspace"])
    }

    @Test
    fun `delayed reconcile revokes the route only when the mounts change`() {
        for ((observed, revoked) in listOf("a".repeat(64) to false, "b".repeat(64) to true)) {
            val jcode = studentJcode().also {
                it.lifecycleStatus = JcodeLifecycleStatus.READY
                it.observedMountHash = observed
            }
            val plan = WorkspaceAccessPlan(jcode.desiredRevision, "a".repeat(64), emptyList(), null)
            val accessPolicy = mock(WorkspaceAccessPolicy::class.java) { invocation ->
                if (invocation.method.name == "plan") plan else RETURNS_DEFAULTS.answer(invocation)
            }
            val operationStore = mock(WorkspaceOperationStore::class.java)
            val operation = operation(WorkspaceOperationTarget.JCODE, jcode.id, WorkspaceOperationAction.RECONCILE_JCODE_ACCESS, jcode.desiredRevision)
            `when`(operationStore.claimNext()).thenReturn(operation)
            `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)
            val deleted = mutableListOf<Long>()
            val redis = mock(RedisService::class.java) { invocation ->
                if (invocation.method.name == "deleteJcodeRoute") deleted += invocation.arguments[0] as Long
                null
            }
            val generator = WebClient.builder().exchangeFunction {
                Mono.just(
                    ClientResponse.create(HttpStatus.OK, ExchangeStrategies.withDefaults())
                        .header("Content-Type", "application/json")
                        .body("""{"state":"READY"}""")
                        .build()
                )
            }.build()
            val withRedis = WorkspaceReconciler(
                operationStore, accessPolicy,
                mock(GeneratorContractVerifier::class.java), CountingAccess(true),
                generator, 1, 5, redis
            )

            withRedis.reconcile()

            assertEquals(if (revoked) listOf(jcode.id) else emptyList<Long>(), deleted)
        }
    }

    @Test
    fun `reconciler waits for a Generator that supports read-only mounts`() {
        val jcode = studentJcode().also { it.lifecycleStatus = JcodeLifecycleStatus.READY }
        val plan = WorkspaceAccessPlan(
            revision = jcode.desiredRevision,
            mountHash = "a".repeat(64),
            assignments = listOf(WorkspaceAssignmentMount(60, "assignment-60", "hw1", readOnly = true)),
            validUntil = null
        )
        val accessPolicy = mock(WorkspaceAccessPolicy::class.java) { invocation ->
            if (invocation.method.name == "plan") plan else RETURNS_DEFAULTS.answer(invocation)
        }
        val operationStore = mock(WorkspaceOperationStore::class.java)
        val operation = operation(WorkspaceOperationTarget.JCODE, jcode.id, WorkspaceOperationAction.RECONCILE_JCODE_ACCESS, jcode.desiredRevision)
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadJcode(jcode.id)).thenReturn(jcode)
        val bodies = mutableListOf<Map<*, *>>()

        reconciler(operationStore, accessPolicy, CountingAccess(true), bodies, """{"state":"READY"}""", generatorSupportsReadOnly = false)
            .reconcile()

        assertTrue(bodies.isEmpty())
        verify(operationStore).defer(operation)
    }

    @Test
    fun `portal and notices use the last known Generator answer without waiting`() {
        val verifier = mock(GeneratorContractVerifier::class.java)
        `when`(verifier.lastKnownSupport(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenReturn(true)
        `when`(verifier.supports(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenThrow(IllegalStateException("busy"))
        val access = ScheduledClosedAssignmentAccess(verifier, "2026-10-01T00:00:00", "")
        assertTrue(access.readOnlyEnabledForDisplay(now))

        val fresh = mock(GeneratorContractVerifier::class.java)
        `when`(fresh.lastKnownSupport(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenReturn(null)
        `when`(fresh.supports(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenThrow(IllegalStateException("down"))
        val unreachable = ScheduledClosedAssignmentAccess(fresh, "2026-10-01T00:00:00", "")
        assertFalse(unreachable.readOnlyEnabledForDisplay(now))
        assertFalse(unreachable.readOnlyEnabledForDisplay(now))
        org.mockito.Mockito.verify(fresh, org.mockito.Mockito.times(1)).supports(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)
    }

    @Test
    fun `IDE warning times come from configuration`() {
        assertEquals(listOf(15, 5, 2), WorkspaceNoticePublisher.parseLeadMinutes(" 2,15,5,5,0,99,x "))
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { WorkspaceNoticePublisher.parseLeadMinutes("0,x") }
    }

    @Test
    fun `finalization keeps descriptors only while read-only access is enabled`() {
        for (enabled in listOf(true, false)) {
            val course = studentJcode().course
            val assignment = assignment(course, 60, now.minusDays(14), now.minusMinutes(1)).also {
                it.scheduleStatus = AssignmentScheduleStatus.CLOSED
                it.finalizationGeneration = 1
            }
            val operationStore = mock(WorkspaceOperationStore::class.java)
            val operation = operation(WorkspaceOperationTarget.ASSIGNMENT, assignment.id, WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION)
            `when`(operationStore.claimNext()).thenReturn(operation)
            `when`(operationStore.loadAssignment(assignment.id)).thenReturn(assignment)
            `when`(operationStore.courseMembershipMutationsSettled(course.id)).thenReturn(true)
            `when`(operationStore.activeCourseJcodesReconciled(course.id, assignment.id, course.workspacePolicyRevision)).thenReturn(true)
            `when`(operationStore.loadAssignmentJcodes(assignment.id, false)).thenReturn(emptyList())
            val bodies = mutableListOf<Map<*, *>>()

            reconciler(operationStore, mock(WorkspaceAccessPolicy::class.java), CountingAccess(enabled), bodies, """{"completed":true}""").reconcile()

            assertEquals(enabled, bodies.single()["retain_workspace_descriptors"])
        }
    }

    @Test
    fun `notice publisher lists upcoming kickoffs and deadlines per course`() {
        val course = studentJcode().course
        val other = course.copy(id = 11, infrastructureKey = "os", clss = 2)
        val kickoff = assignment(course, 63, now.plusMinutes(5), now.plusDays(7)).also {
            it.scheduleStatus = AssignmentScheduleStatus.SCHEDULED
        }
        val deadline = assignment(course, 62, now.minusDays(3), now.plusMinutes(10))
        val writes = mutableMapOf<String, String>()
        val publisher = noticePublisher(listOf(deadline, kickoff), listOf(course, other), writes, enabled = true)

        publisher.publish(now)

        val transitions = mapper.readValue(writes.getValue("jcode:notices:course:alg:1"), Map::class.java)["transitions"] as List<*>
        assertEquals(listOf("KICKOFF", "DEADLINE"), transitions.map { (it as Map<*, *>)["kind"] })
        val deadlineNotice = transitions[1] as Map<*, *>
        assertEquals("Assignment 62", deadlineNotice["assignmentName"])
        assertEquals(listOf(10, 5, 1), deadlineNotice["leadMinutes"])
        assertEquals(listOf(1), (transitions[0] as Map<*, *>)["leadMinutes"])
        assertEquals(true, deadlineNotice["readOnlyAfter"])
        assertEquals(
            now.plusMinutes(10).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            (deadlineNotice["at"] as Number).toLong()
        )
        assertEquals("""{"version":1,"transitions":[]}""", writes.getValue("jcode:notices:course:os:2"))
    }

    @Test
    fun `notice publisher is inert while disabled`() {
        val course = studentJcode().course
        val writes = mutableMapOf<String, String>()
        val publisher = noticePublisher(
            listOf(assignment(course, 62, now.minusDays(3), now.plusMinutes(10))),
            listOf(course),
            writes,
            enabled = false
        )

        publisher.publish()

        assertTrue(writes.isEmpty())
    }

    private fun noticePublisher(
        upcoming: List<Assignment>,
        courses: List<Course>,
        writes: MutableMap<String, String>,
        enabled: Boolean
    ): WorkspaceNoticePublisher {
        val assignments = mock(AssignmentRepository::class.java) { invocation ->
            if (invocation.method.name == "findUpcomingScheduleTransitions") upcoming
            else RETURNS_DEFAULTS.answer(invocation)
        }
        val courseRepository = mock(CourseRepository::class.java) { invocation ->
            if (invocation.method.name == "findByStatusAndWorkspaceRuntimeEnabledTrue") courses
            else RETURNS_DEFAULTS.answer(invocation)
        }
        @Suppress("UNCHECKED_CAST")
        val values = mock(ValueOperations::class.java) { invocation ->
            if (invocation.method.name == "set" && invocation.arguments.size == 3 && invocation.arguments[2] is Duration) {
                writes[invocation.arguments[0] as String] = invocation.arguments[1] as String
            }
            null
        } as ValueOperations<String, String>
        val redis = mock(StringRedisTemplate::class.java)
        `when`(redis.opsForValue()).thenReturn(values)
        return WorkspaceNoticePublisher(assignments, courseRepository, redis, CountingAccess(true), enabled, 15, 120)
    }

    private fun reconciler(
        operationStore: WorkspaceOperationStore,
        accessPolicy: WorkspaceAccessPolicy,
        access: ClosedAssignmentAccess,
        bodies: MutableList<Map<*, *>>,
        response: String,
        generatorSupportsReadOnly: Boolean = true
    ): WorkspaceReconciler {
        val verifier = mock(GeneratorContractVerifier::class.java)
        `when`(verifier.supports(ScheduledClosedAssignmentAccess.GENERATOR_FEATURE)).thenReturn(generatorSupportsReadOnly)
        val strategies = ExchangeStrategies.withDefaults()
        val webClient = WebClient.builder().exchangeFunction { request ->
            if (request.method().name() == "POST") {
                val captured = MockClientHttpRequest(request.method(), request.url())
                @Suppress("UNCHECKED_CAST")
                (request.body() as BodyInserter<Any, ClientHttpRequest>).insert(captured, object : BodyInserter.Context {
                    override fun messageWriters() = strategies.messageWriters()
                    override fun serverRequest(): Optional<ServerHttpRequest> = Optional.empty()
                    override fun hints(): Map<String, Any> = emptyMap()
                }).block()
                bodies += mapper.readValue(captured.bodyAsString.block(), Map::class.java)
            }
            Mono.just(
                ClientResponse.create(HttpStatus.OK, strategies)
                    .header("Content-Type", "application/json")
                    .body(response)
                    .build()
            )
        }.build()
        return WorkspaceReconciler(
            operationStore,
            accessPolicy,
            verifier,
            access,
            webClient,
            1,
            5
        )
    }

    private fun policy(assignments: List<Assignment>, access: ClosedAssignmentAccess): WorkspaceAccessPolicy {
        val repository = mock(AssignmentRepository::class.java)
        assignments.groupBy { it.course.id }.forEach { (courseId, items) ->
            `when`(repository.findByCourseId(courseId)).thenReturn(items)
        }
        return WorkspaceAccessPolicy(repository, access)
    }

    private fun operation(
        target: WorkspaceOperationTarget,
        targetId: Long,
        action: WorkspaceOperationAction,
        desiredRevision: Long? = null
    ) = ClaimedWorkspaceOperation(
        id = 99,
        targetType = target,
        targetId = targetId,
        action = action,
        artifactId = null,
        desiredRevision = desiredRevision,
        idempotencyKey = "00000000-0000-0000-0000-000000000099",
        attempts = 1
    )

    private fun studentJcode(joinedAt: LocalDateTime = now.minusDays(30)): Jcode {
        val course = Course(
            id = 10, name = "Algorithms", infrastructureKey = "alg", year = 2026,
            term = 2, professor = "Professor", clss = 1, vnc = false,
            courseKey = "key", workspacePolicyRevision = 7, workspaceRuntimeEnabled = true
        )
        val user = User(id = 20, email = "student@example.com", role = RoleType.STUDENT, studentNum = 20260001)
        val membership = UserCourses(
            id = 30, course = course, user = user, role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY, createdAt = joinedAt
        )
        return Jcode(
            id = 40, userCourse = membership, course = course, user = user,
            desiredRevision = course.workspacePolicyRevision
        )
    }

    private fun assignment(course: Course, id: Long, kickoff: LocalDateTime, deadline: LocalDateTime) = Assignment(
        id = id,
        course = course,
        name = "Assignment $id",
        description = null,
        workspaceKey = "assignment-$id",
        dirName = "assignment-$id",
        lifecycleStatus = AssignmentLifecycleStatus.ACTIVE,
        scheduleStatus = AssignmentScheduleStatus.OPEN,
        kickoffDate = kickoff,
        deadlineDate = deadline
    )
}
