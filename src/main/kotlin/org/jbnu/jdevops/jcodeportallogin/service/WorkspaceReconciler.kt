package org.jbnu.jdevops.jcodeportallogin.service

import org.jbnu.jdevops.jcodeportallogin.config.GENERATOR_SCOPE_ATTRIBUTE
import org.jbnu.jdevops.jcodeportallogin.entity.*
import org.jbnu.jdevops.jcodeportallogin.util.WorkspaceNaming
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDateTime

private class WorkspaceProvisioningPendingException : RuntimeException()

@Component
class WorkspaceReconciler(
    private val operationStore: WorkspaceOperationStore,
    private val workspaceAccessPolicy: WorkspaceAccessPolicy,
    private val generatorContractVerifier: GeneratorContractVerifier,
    @Qualifier("generatorWorkspaceWebClient") private val generator: WebClient,
    @Value("\${workspace.lifecycle.batch-size:20}") private val batchSize: Int,
    @Value("\${workspace.lifecycle.request-timeout-seconds:90}") private val timeoutSeconds: Long
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val skipped = mapOf("_skipStateUpdate" to true)
    private val superseded = mapOf("_superseded" to true)

    @Scheduled(
        fixedDelayString = "\${workspace.lifecycle.reconcile-delay-ms:3000}",
        initialDelayString = "\${workspace.lifecycle.initial-delay-ms:10000}"
    )
    fun reconcile() {
        repeat(batchSize) {
            val operation = operationStore.claimNext() ?: return
            try {
                val result = execute(operation)
                operationStore.markSucceeded(operation, result)
            } catch (_: WorkspaceProvisioningPendingException) {
                operationStore.defer(operation)
            } catch (error: Exception) {
                logger.error(
                    "Workspace reconcile failed: target={}/{}, action={}, attempt={}",
                    operation.targetType, operation.targetId, operation.action, operation.attempts, error
                )
                operationStore.markFailed(operation, error)
            }
        }
    }

    private fun execute(operation: ClaimedWorkspaceOperation): Map<*, *>? =
        when (operation.targetType) {
            WorkspaceOperationTarget.ASSIGNMENT -> executeAssignment(operation)
            WorkspaceOperationTarget.MEMBERSHIP -> executeMembership(operation)
            WorkspaceOperationTarget.JCODE -> executeJcode(operation)
        }

    private fun executeAssignment(operation: ClaimedWorkspaceOperation): Map<*, *>? {
        val assignment = operationStore.loadAssignment(operation.targetId) ?: return null
        val course = assignment.course
        val namespace = course.namespaceKey ?: Course.namespaceKey(course.infrastructureKey, course.clss)
        when (operation.action) {
            WorkspaceOperationAction.MIGRATE_ASSIGNMENT_PATH,
            WorkspaceOperationAction.PROVISION_ASSIGNMENT -> {
                if (assignment.lifecycleStatus != AssignmentLifecycleStatus.PROVISIONING) {
                    return skipped
                }
                if (course.status != CourseStatus.ACTIVE) {
                    operationStore.cancelAssignmentProvision(assignment.id)
                    return skipped
                }
                return postBatch(
                "/api/workspace/assignments/provision",
                "workspace:write",
                operation.idempotencyKey,
                mapOf(
                    "course_id" to course.id,
                    "namespace" to namespace,
                    "workspace_key" to assignment.workspaceKey,
                    "legacy_dir_name" to assignment.legacyDirName,
                    "display_name" to assignment.name
                )
                )
            }
            WorkspaceOperationAction.UPDATE_ASSIGNMENT_METADATA -> {
                if (assignment.lifecycleStatus != AssignmentLifecycleStatus.ACTIVE ||
                    course.status != CourseStatus.ACTIVE
                ) return skipped
                return postBatch(
                    "/api/workspace/assignments/provision",
                    "workspace:write",
                    operation.idempotencyKey,
                    mapOf(
                        "course_id" to course.id,
                        "namespace" to namespace,
                        "workspace_key" to assignment.workspaceKey,
                        "display_name" to assignment.name
                    )
                )
            }
            WorkspaceOperationAction.DISTRIBUTE_STARTER -> {
                if (assignment.lifecycleStatus in setOf(
                        AssignmentLifecycleStatus.PROVISIONING,
                        AssignmentLifecycleStatus.PROVISION_FAILED
                    )
                ) {
                    throw WorkspaceProvisioningPendingException()
                }
                if (assignment.lifecycleStatus != AssignmentLifecycleStatus.ACTIVE) return skipped
                val artifact = operation.artifactId?.let(operationStore::loadArtifact)
                    ?: throw IllegalStateException("스타터 artifact를 찾을 수 없습니다.")
                if (!operationStore.courseMembershipMutationsSettled(course.id)) {
                    throw WorkspaceProvisioningPendingException()
                }
                if (!operationStore.assignmentInspectorsClosed(assignment.id)) {
                    if (operationStore.hasFailedAssignmentInspectorCleanup(assignment.id)) {
                        throw IllegalStateException("과제 검사 세션 종료 실패로 스타터 코드 배포를 중단했습니다.")
                    }
                    throw WorkspaceProvisioningPendingException()
                }
                if (!operationStore.activeCourseJcodesReconciled(
                        course.id,
                        assignment.id,
                        course.workspacePolicyRevision
                    )) {
                    if (operationStore.hasFailedCourseJcodeReconciliation(course.id, assignment.id)) {
                        throw IllegalStateException("학생 JCode 접근 차단 실패로 스타터 코드 배포를 중단했습니다.")
                    }
                    throw WorkspaceProvisioningPendingException()
                }
                // Record the distribution start only immediately before the first mutating
                // Generator batch. A deadline/course transition while waiting must cancel it.
                if (!operationStore.beginStarterDistribution(artifact.id)) return skipped
                return postBatch(
                    "/api/workspace/assignments/starter/distribute",
                    "workspace:write",
                    operation.idempotencyKey,
                    mapOf(
                        "course_id" to course.id,
                        "namespace" to namespace,
                        "workspace_key" to assignment.workspaceKey,
                        "artifact_key" to artifact.artifactKey,
                        "checksum" to artifact.checksum,
                        "overwrite_policy" to artifact.overwritePolicy.name
                    )
                )
            }
            WorkspaceOperationAction.ARCHIVE_ASSIGNMENT -> {
                if (assignment.lifecycleStatus != AssignmentLifecycleStatus.DELETING) {
                    return skipped
                }
                if (!operationStore.courseMembershipMutationsSettled(course.id)) {
                    throw WorkspaceProvisioningPendingException()
                }
                if (!operationStore.activeCourseJcodesReconciled(
                        course.id,
                        assignment.id,
                        course.workspacePolicyRevision
                    )) {
                    if (operationStore.hasFailedCourseJcodeReconciliation(course.id, assignment.id)) {
                        throw IllegalStateException("학생 JCode 접근 정책 반영 실패로 과제 보관을 중단했습니다.")
                    }
                    throw WorkspaceProvisioningPendingException()
                }
                val jcodes = operationStore.loadAssignmentJcodes(assignment.id)
                return postBatch(
                "/api/workspace/assignments/archive",
                "workspace:write",
                operation.idempotencyKey,
                mapOf(
                    "course_id" to course.id,
                    "namespace" to namespace,
                    "workspace_key" to assignment.workspaceKey,
                    "display_name" to assignment.name,
                    "retention_days" to assignment.archiveRetentionDays,
                    "deployments" to jcodes.map { it.first },
                    "services" to jcodes.map { it.second }
                )
                )
            }
            WorkspaceOperationAction.ARCHIVE_FINAL_SUBMISSION -> {
                if (assignment.lifecycleStatus != AssignmentLifecycleStatus.ACTIVE ||
                    assignment.scheduleStatus != AssignmentScheduleStatus.CLOSED ||
                    assignment.finalizedAt != null) return skipped
                if (assignment.starterDistributionPending) {
                    throw WorkspaceProvisioningPendingException()
                }
                if (!operationStore.courseMembershipMutationsSettled(course.id)) {
                    throw WorkspaceProvisioningPendingException()
                }
                if (!operationStore.activeCourseJcodesReconciled(
                        course.id,
                        assignment.id,
                        course.workspacePolicyRevision
                    )) {
                    if (operationStore.hasFailedCourseJcodeReconciliation(course.id, assignment.id)) {
                        throw IllegalStateException("학생 JCode 접근 정책 반영 실패로 최종본 보관을 중단했습니다.")
                    }
                    throw WorkspaceProvisioningPendingException()
                }
                // Read-only inspector sessions remain available after the student deadline.
                val jcodes = operationStore.loadAssignmentJcodes(
                    assignment.id,
                    includeInspectors = false
                )
                return postBatch(
                "/api/workspace/assignments/finalize",
                "workspace:write",
                operation.idempotencyKey,
                mapOf(
                    "course_id" to course.id,
                    "namespace" to namespace,
                    "workspace_key" to assignment.workspaceKey,
                    "display_name" to assignment.name,
                    "finalization_generation" to assignment.finalizationGeneration,
                    "retention_days" to assignment.archiveRetentionDays,
                    "deployments" to jcodes.map { it.first },
                    "services" to jcodes.map { it.second }
                )
                )
            }
            WorkspaceOperationAction.RESTORE_ASSIGNMENT -> {
                if (assignment.lifecycleStatus != AssignmentLifecycleStatus.PROVISIONING ||
                    assignment.scheduleStatus != AssignmentScheduleStatus.OPEN) return skipped
                if (course.status != CourseStatus.ACTIVE) {
                    operationStore.cancelAssignmentProvision(assignment.id)
                    return skipped
                }
                if (!LocalDateTime.now().isBefore(assignment.deadlineDate)) {
                    operationStore.cancelExpiredAssignmentRestore(assignment.id)
                    return skipped
                }
                val starter = operationStore.loadLatestAssignmentArtifact(assignment.id)
                return postBatch(
                "/api/workspace/assignments/restore",
                "workspace:write",
                operation.idempotencyKey,
                mapOf(
                    "course_id" to course.id,
                    "namespace" to namespace,
                    "workspace_key" to assignment.workspaceKey,
                    "display_name" to assignment.name,
                    "retention_days" to assignment.archiveRetentionDays,
                    "finalization_generation" to assignment.finalizationGeneration.coerceAtLeast(1),
                    "starter_artifact_key" to starter?.artifactKey,
                    "starter_checksum" to starter?.checksum,
                    "starter_overwrite_policy" to (starter?.overwritePolicy?.name ?: "PRESERVE_EXISTING")
                )
                )
            }
            else -> throw IllegalStateException("과제 대상에 잘못된 작업입니다: ${operation.action}")
        }
    }

    private fun executeMembership(operation: ClaimedWorkspaceOperation): Map<*, *>? {
        val membership = operationStore.loadMembership(operation.targetId) ?: return null
        val course = membership.course
        val namespace = course.namespaceKey ?: Course.namespaceKey(course.infrastructureKey, course.clss)
        val jcodeNames = operationStore.loadMembershipJcodes(membership.id)
        return when (operation.action) {
            WorkspaceOperationAction.PROVISION_MEMBERSHIP -> if (membership.lifecycleStatus in setOf(
                MembershipStatus.PROVISIONING,
                MembershipStatus.READY
            )) {
                if (course.status != CourseStatus.ACTIVE) {
                    operationStore.cancelMembershipProvision(membership.id)
                    return skipped
                }
                post(
                "/api/workspace/students/provision",
                "workspace:write",
                operation.idempotencyKey,
                mapOf(
                    "course_id" to course.id,
                    "namespace" to namespace,
                    "student_num" to membership.user.studentNum.toString(),
                    "display_name" to WorkspaceNaming.displayName(membership.user),
                    "workspace_keys" to operationStore.loadActiveAssignmentKeys(course.id),
                    "workspace_labels" to operationStore.loadActiveAssignmentLabels(course.id),
                    "artifacts" to operationStore.loadLatestCourseArtifacts(course.id).map {
                        mapOf(
                            "workspace_key" to it.assignment.workspaceKey,
                            "artifact_key" to it.artifactKey,
                            "checksum" to it.checksum,
                            // Membership preparation must never replace work left by an earlier role/state.
                            "overwrite_policy" to StarterOverwritePolicy.PRESERVE_EXISTING.name
                        )
                    }
                )
                )
            } else skipped
            WorkspaceOperationAction.DELETE_MEMBERSHIP -> if (membership.lifecycleStatus in setOf(
                MembershipStatus.DELETE_PENDING,
                MembershipStatus.DELETE_FAILED
            )) post(
                "/api/workspace/students/archive",
                "workspace:write",
                operation.idempotencyKey,
                mapOf(
                    "course_id" to course.id,
                    "namespace" to namespace,
                    "student_num" to membership.user.studentNum.toString(),
                    "deployments" to jcodeNames.map { it.first },
                    "services" to jcodeNames.map { it.second },
                    "retention_days" to 90,
                    "archive_key" to operation.idempotencyKey
                )
            ) else skipped
            else -> throw IllegalStateException("가입 대상에 잘못된 작업입니다: ${operation.action}")
        }
    }

    private fun executeJcode(operation: ClaimedWorkspaceOperation): Map<*, *>? {
        val jcode = operationStore.loadJcode(operation.targetId) ?: return null
        val course = jcode.course
        val namespace = course.namespaceKey ?: Course.namespaceKey(course.infrastructureKey, course.clss)
        return when (operation.action) {
            WorkspaceOperationAction.PROVISION_JCODE -> if (jcode.lifecycleStatus == JcodeLifecycleStatus.PROVISIONING) {
                if (jcode.userCourse.lifecycleStatus == MembershipStatus.PROVISIONING ||
                    course.status == CourseStatus.PROVISIONING
                ) {
                    throw WorkspaceProvisioningPendingException()
                }
                if (jcode.userCourse.lifecycleStatus != MembershipStatus.READY ||
                    course.status != CourseStatus.ACTIVE ||
                    !course.workspaceRuntimeEnabled
                ) {
                    operationStore.cancelJcodeProvision(jcode.id)
                    return null
                }
                val plan = workspaceAccessPolicy.plan(jcode)
                val assignmentProvisionInvalid =
                    jcode.kind == JcodeKind.STANDARD &&
                        jcode.assignment != null &&
                        plan.assignments.none { it.assignmentId == jcode.assignment.id }
                val inspectorProvisionInvalid =
                    jcode.kind == JcodeKind.INSPECTOR &&
                        (
                            jcode.assignment == null ||
                                plan.assignments.none { it.assignmentId == jcode.assignment.id } ||
                                jcode.expiresAt?.let { !LocalDateTime.now().isBefore(it) } != false
                            )
                if (assignmentProvisionInvalid || inspectorProvisionInvalid) {
                    operationStore.cancelJcodeProvision(jcode.id)
                    return null
                }
                val provisioned = post(
                    "/api/jcode",
                    "jcode:write",
                    operation.idempotencyKey,
                    jcodePayload(jcode, namespace, plan)
                )
                val readiness = getJcodeReadiness(
                    course.id,
                    namespace,
                    jcode.deploymentName,
                    jcode.serviceName,
                    plan.revision,
                    plan.mountHash
                )
                when (readiness["state"]?.toString()) {
                    "READY" -> mapOf(
                        "jcodeUrl" to (provisioned?.get("jcodeUrl")
                            ?: "http://${jcode.serviceName}.$namespace.svc.cluster.local:8080"),
                        "policyRevision" to plan.revision,
                        "mountHash" to plan.mountHash
                    )
                    "FAILED" -> throw IllegalStateException(
                        "JCode workload readiness failed: ${readiness["reasonCode"] ?: "UNKNOWN"}"
                    )
                    else -> throw WorkspaceProvisioningPendingException()
                }
            } else skipped
            WorkspaceOperationAction.RECONCILE_JCODE_ACCESS -> {
                if (operation.desiredRevision == null || operation.desiredRevision != jcode.desiredRevision) {
                    superseded
                } else if (jcode.userCourse.lifecycleStatus == MembershipStatus.PROVISIONING) {
                    throw WorkspaceProvisioningPendingException()
                } else if (jcode.lifecycleStatus == JcodeLifecycleStatus.PROVISIONING) {
                    superseded
                } else if (
                    jcode.lifecycleStatus != JcodeLifecycleStatus.READY ||
                    course.status != CourseStatus.ACTIVE ||
                    !course.workspaceRuntimeEnabled ||
                    jcode.userCourse.lifecycleStatus != MembershipStatus.READY
                ) {
                    superseded
                } else {
                    val plan = workspaceAccessPolicy.plan(jcode)
                    post(
                        "/api/jcode",
                        "jcode:write",
                        operation.idempotencyKey,
                        jcodePayload(jcode, namespace, plan)
                    )
                    val readiness = getJcodeReadiness(
                        course.id,
                        namespace,
                        jcode.deploymentName,
                        jcode.serviceName,
                        plan.revision,
                        plan.mountHash
                    )
                    when (readiness["state"]?.toString()) {
                        "READY" -> mapOf("policyRevision" to plan.revision, "mountHash" to plan.mountHash)
                        "FAILED", "DRIFTED" -> throw IllegalStateException(
                            "JCode access reconcile failed: ${readiness["reasonCode"] ?: "UNKNOWN"}"
                        )
                        else -> throw WorkspaceProvisioningPendingException()
                    }
                }
            }
            WorkspaceOperationAction.DELETE_JCODE -> if (jcode.lifecycleStatus in setOf(
                JcodeLifecycleStatus.DELETE_PENDING,
                JcodeLifecycleStatus.DELETE_FAILED,
                JcodeLifecycleStatus.ARCHIVED
            )) delete(
                "/api/jcode",
                "jcode:delete",
                operation.idempotencyKey,
                mapOf(
                    "course_id" to course.id,
                    "namespace" to namespace,
                    "deployment_name" to jcode.deploymentName,
                    "service_name" to jcode.serviceName
                )
            ) else skipped
            else -> throw IllegalStateException("JCode 대상에 잘못된 작업입니다: ${operation.action}")
        }
    }

    private fun getJcodeReadiness(
        courseId: Long,
        namespace: String,
        deploymentName: String,
        serviceName: String,
        policyRevision: Long? = null,
        mountHash: String? = null
    ): Map<*, *> = generator.get()
        .also { generatorContractVerifier.requireCompatible() }
        .uri { builder ->
            builder.path("/api/jcode/status")
                .queryParam("course_id", courseId)
                .queryParam("namespace", namespace)
                .queryParam("deployment_name", deploymentName)
                .queryParam("service_name", serviceName)
                .apply {
                    if (policyRevision != null) queryParam("policy_revision", policyRevision)
                    if (mountHash != null) queryParam("mount_hash", mountHash)
                }
                .build()
        }
        .attribute(GENERATOR_SCOPE_ATTRIBUTE, "jcode:read")
        .retrieve()
        .bodyToMono(Map::class.java)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .block()
        ?: throw IllegalStateException("Generator가 JCode 준비 상태를 반환하지 않았습니다.")

    private fun jcodePayload(
        jcode: Jcode,
        namespace: String,
        plan: WorkspaceAccessPlan
    ): Map<String, Any?> {
        val course = jcode.course
        return mapOf(
            "course_id" to course.id,
            "namespace" to namespace,
            "deployment_name" to jcode.deploymentName,
            "service_name" to jcode.serviceName,
            "app_label" to jcode.deploymentName,
            "file_path" to if (jcode.snapshot) {
                "${course.infrastructureKey.lowercase()}-${course.clss}"
            } else "workspace/${course.infrastructureKey.lowercase()}-${course.clss}-${jcode.user.studentNum}",
            "student_num" to jcode.user.studentNum.toString(),
            "use_vnc" to course.useVnc,
            "environment_profile" to course.environmentProfile.name,
            "use_jupyter" to course.useJupyter,
            "base_image" to course.baseImage,
            "resource_profile" to course.resourceProfile.name,
            "egress_policy" to course.egressPolicy.name,
            "workspace_scope" to if (jcode.kind == JcodeKind.INSPECTOR) {
                WorkspaceScope.ASSIGNMENT.name
            } else course.workspaceScope.name,
            "assignment_workspace_key" to jcode.assignment?.workspaceKey,
            "workspace_display_name" to WorkspaceNaming.displayName(jcode.user),
            "use_snapshot" to jcode.snapshot,
            "hw_count" to course.hwCount,
            "prac_count" to if (course.pracEnabled) course.pracCount else 0,
            "assignment_dirs" to plan.assignments.map { it.workspaceKey },
            "assignment_labels" to plan.assignments.associate { it.workspaceKey to it.displayName },
            "policy_revision" to plan.revision,
            "mount_hash" to plan.mountHash,
            "session_kind" to jcode.kind.name,
            "read_only_workspace" to (jcode.kind == JcodeKind.INSPECTOR)
        )
    }

    private fun post(uri: String, scope: String, idempotencyKey: String, body: Map<String, Any?>): Map<*, *>? =
        generator.post()
            .also { generatorContractVerifier.requireCompatible() }
            .uri(uri)
            .attribute(GENERATOR_SCOPE_ATTRIBUTE, scope)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .block()

    private fun postBatch(
        uri: String,
        scope: String,
        idempotencyKey: String,
        body: Map<String, Any?>
    ): Map<*, *>? {
        val result = generator.post()
            .also { generatorContractVerifier.requireCompatible() }
            .uri(uri)
            .attribute(GENERATOR_SCOPE_ATTRIBUTE, scope)
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Workspace-Batch-Protocol", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .block()
            ?: throw IllegalStateException("Generator batch 응답이 없습니다.")
        if (result["completed"] != true) throw WorkspaceProvisioningPendingException()
        return result
    }

    private fun delete(uri: String, scope: String, idempotencyKey: String, body: Map<String, Any?>): Map<*, *>? =
        generator.method(org.springframework.http.HttpMethod.DELETE)
            .also { generatorContractVerifier.requireCompatible() }
            .uri(uri)
            .attribute(GENERATOR_SCOPE_ATTRIBUTE, scope)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .block()
}
