package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.jbnu.jdevops.jcodeportallogin.entity.CourseInfrastructureAction
import org.jbnu.jdevops.jcodeportallogin.entity.CourseInfrastructureOperation
import org.jbnu.jdevops.jcodeportallogin.entity.CourseInfrastructureOperationStatus
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.repo.CourseInfrastructureOperationRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

class CourseInfrastructureOperationStoreTest {
    private val operationRepository = mock(CourseInfrastructureOperationRepository::class.java)
    private val courseRepository = mock(CourseRepository::class.java)
    private val store = CourseInfrastructureOperationStore(operationRepository, courseRepository, 3, 300)

    @Test
    fun `transient infrastructure failure keeps course provisioning`() {
        val course = course()
        val operation = operation(attempts = 1)
        `when`(operationRepository.findById(operation.id)).thenReturn(Optional.of(operation))

        store.markFailed(claimed(operation), RuntimeException("internal infrastructure detail"))

        assertEquals(CourseInfrastructureOperationStatus.PENDING, operation.status)
        assertEquals(CourseStatus.PROVISIONING, course.status)
        verify(courseRepository, never()).save(course)
    }

    @Test
    fun `terminal infrastructure failure exposes only error state`() {
        val course = course()
        val operation = operation(attempts = 3)
        `when`(operationRepository.findById(operation.id)).thenReturn(Optional.of(operation))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))

        store.markFailed(claimed(operation), RuntimeException("internal infrastructure detail"))

        assertEquals(CourseInfrastructureOperationStatus.FAILED, operation.status)
        assertEquals(CourseStatus.ERROR, course.status)
        verify(courseRepository).save(course)
    }

    @Test
    fun `cancelled stale completion cannot activate course`() {
        val course = course()
        val operation = operation(attempts = 1).also {
            it.status = CourseInfrastructureOperationStatus.CANCELLED
        }
        `when`(operationRepository.findById(operation.id)).thenReturn(Optional.of(operation))

        store.markSucceeded(claimed(operation))

        assertEquals(CourseStatus.PROVISIONING, course.status)
        assertEquals(CourseInfrastructureOperationStatus.CANCELLED, operation.status)
        verify(courseRepository, never()).save(course)
        verify(operationRepository, never()).save(operation)
    }

    @Test
    fun `provision completion after cancellation cannot enable workspace runtime`() {
        val course = course().also {
            it.status = CourseStatus.ARCHIVING
            it.workspaceRuntimeEnabled = false
        }
        val operation = operation(attempts = 1)
        `when`(operationRepository.findById(operation.id)).thenReturn(Optional.of(operation))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))

        store.markSucceeded(claimed(operation))

        assertEquals(CourseStatus.ARCHIVING, course.status)
        assertEquals(false, course.workspaceRuntimeEnabled)
    }

    @Test
    fun `provision failure after cancellation cannot replace archiving state`() {
        val course = course().also { it.status = CourseStatus.ARCHIVING }
        val operation = operation(attempts = 3)
        `when`(operationRepository.findById(operation.id)).thenReturn(Optional.of(operation))
        `when`(courseRepository.findById(course.id)).thenReturn(Optional.of(course))

        store.markFailed(claimed(operation), RuntimeException("late failure"))

        assertEquals(CourseInfrastructureOperationStatus.FAILED, operation.status)
        assertEquals(CourseStatus.ARCHIVING, course.status)
        verify(courseRepository, never()).save(course)
    }

    @Test
    fun `different infrastructure actions are queued behind each other`() {
        `when`(operationRepository.existsByCourseIdAndActionAndStatusIn(
            10,
            CourseInfrastructureAction.SYNC_NAMESPACE_METADATA,
            setOf(CourseInfrastructureOperationStatus.PENDING, CourseInfrastructureOperationStatus.PROCESSING)
        )).thenReturn(false)

        store.enqueue(10, CourseInfrastructureAction.SYNC_NAMESPACE_METADATA)

        verify(operationRepository).save(org.mockito.ArgumentMatchers.any(CourseInfrastructureOperation::class.java))
    }

    @Test
    fun `same active infrastructure action is not duplicated`() {
        `when`(operationRepository.existsByCourseIdAndActionAndStatusIn(
            10,
            CourseInfrastructureAction.DELETE_WORKLOADS,
            setOf(CourseInfrastructureOperationStatus.PENDING, CourseInfrastructureOperationStatus.PROCESSING)
        )).thenReturn(true)

        store.enqueue(10, CourseInfrastructureAction.DELETE_WORKLOADS)

        verify(operationRepository, never()).save(org.mockito.ArgumentMatchers.any(CourseInfrastructureOperation::class.java))
    }

    @Test
    fun `expired infrastructure claim cannot overwrite a newer attempt`() {
        val course = course()
        val operation = operation(attempts = 2)
        `when`(operationRepository.findById(operation.id)).thenReturn(Optional.of(operation))

        store.markSucceeded(claimed(operation, attempts = 1))
        store.markFailed(claimed(operation, attempts = 1), RuntimeException("stale"))

        assertEquals(CourseInfrastructureOperationStatus.PROCESSING, operation.status)
        assertEquals(CourseStatus.PROVISIONING, course.status)
        verify(operationRepository, never()).save(operation)
        verify(courseRepository, never()).save(course)
    }

    @Test
    fun `readiness deferral does not consume an attempt`() {
        val operation = operation(attempts = 2)
        `when`(operationRepository.findById(operation.id)).thenReturn(Optional.of(operation))

        store.defer(claimed(operation))

        assertEquals(CourseInfrastructureOperationStatus.PENDING, operation.status)
        assertEquals(1, operation.attempts)
        verify(operationRepository).save(operation)
    }

    private fun operation(attempts: Int) = CourseInfrastructureOperation(
        id = 7,
        courseId = 10,
        action = CourseInfrastructureAction.PROVISION_NAMESPACE,
        status = CourseInfrastructureOperationStatus.PROCESSING,
        attempts = attempts
    )

    private fun claimed(operation: CourseInfrastructureOperation, attempts: Int = operation.attempts) =
        ClaimedCourseInfrastructureOperation(
            id = operation.id,
            courseId = operation.courseId,
            action = operation.action,
            idempotencyKey = operation.idempotencyKey,
            attempts = attempts
        )

    private fun course() = Course(
        id = 10,
        name = "Algorithms",
        infrastructureKey = "ALG",
        year = 2026,
        term = 1,
        professor = "Professor",
        clss = 1,
        namespaceKey = "jcode-alg-1",
        vnc = false,
        courseKey = "course-key",
        status = CourseStatus.PROVISIONING
    )
}
