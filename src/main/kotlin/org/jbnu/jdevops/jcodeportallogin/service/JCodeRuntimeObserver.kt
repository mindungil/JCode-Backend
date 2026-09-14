package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.config.GENERATOR_SCOPE_ATTRIBUTE
import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime

data class JCodeRuntimeTarget(
    val id: Long,
    val courseId: Long,
    val namespace: String,
    val deploymentName: String,
    val serviceName: String,
    val desiredRevision: Long,
    val desiredMountHash: String?
)

data class JCodeRuntimeResult(val state: JcodeObservedStatus, val reasonCode: String)

@Service
class JCodeRuntimeStateStore(
    private val jCodeRepository: JCodeRepository,
    private val courseRepository: CourseRepository,
    private val workspaceOperationStore: WorkspaceOperationStore,
    private val courseInfrastructureOperationStore: CourseInfrastructureOperationStore,
    private val redisService: RedisService,
    private val workspaceAccessPolicy: WorkspaceAccessPolicy
) {
    @Transactional(readOnly = true)
    fun target(id: Long): JCodeRuntimeTarget {
        val jcode = jCodeRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "JCode not found")
        }
        val course = jcode.course
        return JCodeRuntimeTarget(
            id = jcode.id,
            courseId = course.id,
            namespace = course.namespaceKey ?: Course.namespaceKey(course.infrastructureKey, course.clss),
            deploymentName = jcode.deploymentName,
            serviceName = jcode.serviceName,
            desiredRevision = jcode.desiredRevision,
            desiredMountHash = jcode.desiredMountHash
        )
    }

    @Transactional
    fun recordAndRepair(id: Long, result: JCodeRuntimeResult): Boolean {
        val jcode = jCodeRepository.findByIdForUpdate(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "JCode not found")
        }
        val now = LocalDateTime.now()
        jcode.observedStatus = result.state
        jcode.observedReason = result.reasonCode.take(128)
        jcode.lastObservedAt = now

        if (result.state == JcodeObservedStatus.READY) {
            if (
                !jcode.snapshot &&
                jcode.kind == JcodeKind.STANDARD &&
                (jcode.observedRevision != jcode.desiredRevision || jcode.observedMountHash.isNullOrBlank())
            ) {
                redisService.deleteJcodeRoute(jcode.id)
                workspaceOperationStore.enqueueJcodeAccessReconcile(jcode.id, jcode.desiredRevision)
                jCodeRepository.save(jcode)
                return false
            }
            jCodeRepository.save(jcode)
            return jcode.lifecycleStatus == JcodeLifecycleStatus.READY
        }
        if (jcode.lifecycleStatus != JcodeLifecycleStatus.READY) {
            jCodeRepository.save(jcode)
            return false
        }
        redisService.deleteJcodeRoute(jcode.id)

        if (result.reasonCode in setOf(
                "POLICY_REVISION_MISMATCH",
                "MOUNT_HASH_MISMATCH",
                "POLICY_POD_MISMATCH"
            )) {
            workspaceOperationStore.enqueueJcodeAccessReconcile(jcode.id, jcode.desiredRevision)
            jCodeRepository.save(jcode)
            return false
        }

        val assignmentEligible = jcode.assignment?.let {
            it.lifecycleStatus == AssignmentLifecycleStatus.ACTIVE &&
                (jcode.kind == JcodeKind.INSPECTOR || workspaceAccessPolicy.isStudentAccessible(it, now))
        } ?: true
        val eligible = jcode.course.status == CourseStatus.ACTIVE &&
            jcode.course.workspaceRuntimeEnabled &&
            jcode.userCourse.lifecycleStatus == MembershipStatus.READY &&
            assignmentEligible
        if (!eligible) {
            jCodeRepository.save(jcode)
            return false
        }

        if (result.state == JcodeObservedStatus.FAILED) {
            // Reconcile is also an idempotent workload repair. Keeping READY avoids a queue
            // deadlock when an older access reconcile is already ahead of a provision action.
            workspaceOperationStore.enqueueJcodeAccessReconcile(jcode.id, jcode.desiredRevision)
            jCodeRepository.save(jcode)
            return false
        }

        val repairable = result.reasonCode in setOf(
            "NAMESPACE_MISSING",
            "DEPLOYMENT_MISSING",
            "SERVICE_MISSING",
            "DEPLOYMENT_NOT_READY",
            "SERVICE_ENDPOINT_NOT_READY",
            "SERVICE_SPEC_DRIFT"
        )
        if (!repairable) {
            jCodeRepository.save(jcode)
            return false
        }

        if (result.reasonCode == "NAMESPACE_MISSING") {
            val course = courseRepository.findByIdForUpdate(jcode.course.id).orElseThrow()
            if (!course.workspaceRuntimeEnabled || course.status != CourseStatus.ACTIVE) {
                jCodeRepository.save(jcode)
                return false
            }
            course.status = CourseStatus.PROVISIONING
            course.workspaceRuntimeEnabled = false
            courseInfrastructureOperationStore.enqueue(
                course.id,
                CourseInfrastructureAction.PROVISION_NAMESPACE
            )
        }
        jcode.lifecycleStatus = JcodeLifecycleStatus.PROVISIONING
        jcode.lastError = null
        jCodeRepository.save(jcode)
        workspaceOperationStore.enqueueOnce(
            WorkspaceOperationTarget.JCODE,
            jcode.id,
            WorkspaceOperationAction.PROVISION_JCODE,
            desiredRevision = jcode.desiredRevision
        )
        return false
    }
}

@Service
class JCodeRuntimeObserver(
    private val stateStore: JCodeRuntimeStateStore,
    private val generatorContractVerifier: GeneratorContractVerifier,
    @Qualifier("generatorWorkspaceWebClient") private val generator: WebClient,
    @Value("\${jcode.runtime.observe-on-launch:true}") private val observeOnLaunch: Boolean,
    @Value("\${jcode.runtime.request-timeout-seconds:5}") private val timeoutSeconds: Long
) {
    fun requireReady(jcodeId: Long) {
        if (!observeOnLaunch) return
        val target = stateStore.target(jcodeId)
        val response = try {
            generatorContractVerifier.requireCompatible()
            generator.get()
                .uri { builder ->
                    builder.path("/api/jcode/status")
                        .queryParam("course_id", target.courseId)
                        .queryParam("namespace", target.namespace)
                        .queryParam("deployment_name", target.deploymentName)
                        .queryParam("service_name", target.serviceName)
                        .queryParam("policy_revision", target.desiredRevision)
                        .apply {
                            target.desiredMountHash?.let { queryParam("mount_hash", it) }
                        }
                        .build()
                }
                .attribute(GENERATOR_SCOPE_ATTRIBUTE, "jcode:read")
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block()
                ?: throw IllegalStateException("empty Generator response")
        } catch (_: Exception) {
            throw PublicApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "JCODE_RUNTIME_UNAVAILABLE",
                "JCode 실행 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요."
            )
        }

        val state = runCatching {
            JcodeObservedStatus.valueOf(response["state"]?.toString() ?: "UNKNOWN")
        }.getOrDefault(JcodeObservedStatus.UNKNOWN)
        val result = JCodeRuntimeResult(
            state = state,
            reasonCode = response["reasonCode"]?.toString() ?: "UNKNOWN"
        )
        if (!stateStore.recordAndRepair(jcodeId, result)) {
            throw PublicApiException(
                HttpStatus.CONFLICT,
                when (state) {
                    JcodeObservedStatus.FAILED -> "JCODE_RECOVERY_REQUIRED"
                    JcodeObservedStatus.DRIFTED -> "JCODE_POLICY_APPLYING"
                    else -> "JCODE_PREPARING"
                },
                when (state) {
                    JcodeObservedStatus.FAILED -> "JCode 실행 환경 복구가 필요합니다. 관리자에게 문의해주세요."
                    JcodeObservedStatus.DRIFTED -> "JCode 실행 환경 설정을 확인하고 있습니다. 잠시 후 다시 시도해주세요."
                    else -> "JCode 실행 환경을 준비하고 있습니다. 잠시 후 다시 시도해주세요."
                }
            )
        }
    }
}
