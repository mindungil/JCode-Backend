package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.CourseInfrastructureAction
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.reactive.function.client.WebClient

class CourseInfrastructureReconcilerTest {
    private val operationStore = mock(CourseInfrastructureOperationStore::class.java)
    private val workspaceOperationStore = mock(WorkspaceOperationStore::class.java)
    private val client = WebClient.builder().build()
    private val reconciler = CourseInfrastructureReconciler(
        operationStore,
        workspaceOperationStore,
        client,
        client,
        1,
        1
    )

    @Test
    fun `workload deletion waits for final snapshots and JCode cleanup`() {
        val operation = ClaimedCourseInfrastructureOperation(
            id = 7,
            courseId = 10,
            action = CourseInfrastructureAction.DELETE_WORKLOADS,
            idempotencyKey = "00000000-0000-0000-0000-000000000007",
            attempts = 1
        )
        val course = Course(
            id = 10,
            name = "Algorithms",
            infrastructureKey = "alg",
            year = 2026,
            term = 2,
            professor = "Professor",
            clss = 1,
            vnc = false,
            courseKey = "course-key",
            namespaceKey = "jcode-alg-1",
            status = CourseStatus.TERMINATING
        )
        `when`(operationStore.claimNext()).thenReturn(operation)
        `when`(operationStore.loadCourse(operation)).thenReturn(course)
        `when`(workspaceOperationStore.courseTerminationSettled(course.id)).thenReturn(false)

        reconciler.reconcile()

        verify(operationStore).defer(operation)
        verify(operationStore, never()).markSucceeded(operation)
    }
}
