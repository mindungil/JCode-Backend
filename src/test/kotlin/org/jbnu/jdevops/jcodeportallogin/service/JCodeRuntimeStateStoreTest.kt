package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

class JCodeRuntimeStateStoreTest {
    private val repository = mock(JCodeRepository::class.java)
    private val operations = mock(WorkspaceOperationStore::class.java)
    private val courseOperations = mock(CourseInfrastructureOperationStore::class.java)
    private val courseRepository = mock(CourseRepository::class.java)
    private val redisService = mock(RedisService::class.java)
    private val workspaceAccessPolicy = mock(WorkspaceAccessPolicy::class.java)
    private val store = JCodeRuntimeStateStore(
        repository,
        courseRepository,
        operations,
        courseOperations,
        redisService,
        workspaceAccessPolicy
    )

    @Test
    fun `missing deployment is repaired once only for runtime-enabled course`() {
        val jcode = jcode(runtimeEnabled = true)
        `when`(repository.findByIdForUpdate(jcode.id)).thenReturn(Optional.of(jcode))

        val ready = store.recordAndRepair(
            jcode.id,
            JCodeRuntimeResult(JcodeObservedStatus.MISSING, "DEPLOYMENT_MISSING")
        )

        assertFalse(ready)
        assertEquals(JcodeLifecycleStatus.PROVISIONING, jcode.lifecycleStatus)
        assertEquals(JcodeObservedStatus.MISSING, jcode.observedStatus)
        verify(redisService).deleteJcodeRoute(jcode.id)
        verify(operations).enqueueOnce(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE,
            desiredRevision = jcode.desiredRevision
        )
    }

    @Test
    fun `historical ready row is observed but never recreated`() {
        val jcode = jcode(runtimeEnabled = false)
        `when`(repository.findByIdForUpdate(jcode.id)).thenReturn(Optional.of(jcode))

        val ready = store.recordAndRepair(
            jcode.id,
            JCodeRuntimeResult(JcodeObservedStatus.MISSING, "DEPLOYMENT_MISSING")
        )

        assertFalse(ready)
        assertEquals(JcodeLifecycleStatus.READY, jcode.lifecycleStatus)
        verify(operations, never()).enqueueOnce(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE
        )
    }

    @Test
    fun `mount policy drift queues access reconcile without reprovisioning the JCode`() {
        val jcode = jcode(runtimeEnabled = true).also {
            it.desiredRevision = 9
            it.observedRevision = 8
        }
        `when`(repository.findByIdForUpdate(jcode.id)).thenReturn(Optional.of(jcode))

        val ready = store.recordAndRepair(
            jcode.id,
            JCodeRuntimeResult(JcodeObservedStatus.DRIFTED, "POLICY_REVISION_MISMATCH")
        )

        assertFalse(ready)
        assertEquals(JcodeLifecycleStatus.READY, jcode.lifecycleStatus)
        verify(operations).enqueueJcodeAccessReconcile(jcode.id, 9)
        verify(operations, never()).enqueueOnce(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE
        )
    }

    @Test
    fun `runtime readiness failure uses access reconcile without changing ready lifecycle`() {
        val jcode = jcode(runtimeEnabled = true)
        `when`(repository.findByIdForUpdate(jcode.id)).thenReturn(Optional.of(jcode))

        val ready = store.recordAndRepair(
            jcode.id,
            JCodeRuntimeResult(JcodeObservedStatus.FAILED, "DEPLOYMENT_PROGRESS_DEADLINE")
        )

        assertFalse(ready)
        assertEquals(JcodeLifecycleStatus.READY, jcode.lifecycleStatus)
        assertEquals(JcodeObservedStatus.FAILED, jcode.observedStatus)
        verify(operations).enqueueJcodeAccessReconcile(jcode.id, jcode.desiredRevision)
        verify(operations, never()).enqueueOnce(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE
        )
    }

    private fun jcode(runtimeEnabled: Boolean): Jcode {
        val course = Course(
            id = 3,
            name = "Algorithms",
            infrastructureKey = "alg",
            year = 2026,
            term = 2,
            professor = "Professor",
            clss = 1,
            namespaceKey = "jcode-alg-1",
            vnc = false,
            status = CourseStatus.ACTIVE,
            workspaceRuntimeEnabled = runtimeEnabled,
            courseKey = "course-key"
        )
        val user = User(id = 4, email = "student@example.com", studentNum = 20260001, role = RoleType.STUDENT)
        val membership = UserCourses(
            id = 5,
            course = course,
            user = user,
            role = RoleType.STUDENT,
            lifecycleStatus = MembershipStatus.READY
        )
        return Jcode(
            id = 6,
            userCourse = membership,
            course = course,
            user = user,
            lifecycleStatus = JcodeLifecycleStatus.READY,
            deploymentName = "jcode-alg-1-20260001",
            serviceName = "jcode-alg-1-20260001-svc"
        )
    }
}
