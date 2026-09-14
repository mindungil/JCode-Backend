package org.jbnu.jdevops.jcodeportallogin.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jbnu.jdevops.jcodeportallogin.dto.jcode.RedirectDto
import org.jbnu.jdevops.jcodeportallogin.entity.CourseStatus
import org.jbnu.jdevops.jcodeportallogin.repo.AssignmentRepository
import org.jbnu.jdevops.jcodeportallogin.repo.CourseRepository
import org.jbnu.jdevops.jcodeportallogin.repo.JCodeRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserCoursesRepository
import org.jbnu.jdevops.jcodeportallogin.repo.UserRepository
import org.jbnu.jdevops.jcodeportallogin.service.RedisService
import org.jbnu.jdevops.jcodeportallogin.exception.PublicApiException
import org.jbnu.jdevops.jcodeportallogin.service.JCodeRuntimeObserver
import org.jbnu.jdevops.jcodeportallogin.util.AuthorizationUtil
import org.jbnu.jdevops.jcodeportallogin.util.JwtUtil
import org.jbnu.jdevops.jcodeportallogin.util.WorkspaceNaming
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

@Tag(name = "Redirect API", description = "JCode(Node.js 서버)로의 리다이렉션 관련 API")
@RestController
@RequestMapping("/api/redirect")
class RedirectController(
    private val jwtUtil: JwtUtil,
    private val redisService: RedisService,
    private val jCodeRepository: JCodeRepository,
    private val userRepository: UserRepository,
    private val courseRepository: CourseRepository,
    private val assignmentRepository: AssignmentRepository,
    private val userCoursesRepository: UserCoursesRepository,
    private val jCodeRuntimeObserver: JCodeRuntimeObserver,
    private val workspaceAccessPolicy: org.jbnu.jdevops.jcodeportallogin.service.WorkspaceAccessPolicy,
    private val workspaceOperationStore: org.jbnu.jdevops.jcodeportallogin.service.WorkspaceOperationStore,
    private val jCodeService: org.jbnu.jdevops.jcodeportallogin.service.JCodeService
) {

    @Value("\${router.url}")  // 환경 변수에서 Node.js URL 가져오기
    private lateinit var routerUrl: String

    // Node.js 서버로 리다이렉션 (JCode)
    @Operation(
        summary = "JCode(Node.js 서버) 리다이렉션",
        description = "사용자 정보를 Redis에 저장 후 UUID를 파라미터로 전달하여 Node.js 서버에 리다이렉트 합니다."
    )
    @PostMapping
    fun redirectToNode(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
        @RequestBody redirectRequest: RedirectDto
    ): ResponseEntity<Map<String, String>> {

        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization Token")

        val currentEmail = authentication.principal as? String
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing email in authentication")
        val currentUser = userRepository.findByEmail(currentEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Current user not found")
        val user = userRepository.findByEmail(redirectRequest.userEmail)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        val course = courseRepository.findById(redirectRequest.courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found") }

        if (course.status != CourseStatus.ACTIVE) {
            throw PublicApiException(HttpStatus.CONFLICT, "COURSE_UNAVAILABLE", "종료되거나 보관된 강의에는 진입할 수 없습니다.")
        }
        val targetMembership = userCoursesRepository.findByUserIdAndCourseId(user.id, course.id)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "대상 사용자가 해당 강의에 소속되어 있지 않습니다.")
        if (targetMembership.lifecycleStatus != org.jbnu.jdevops.jcodeportallogin.entity.MembershipStatus.READY) {
            throw PublicApiException(HttpStatus.CONFLICT, "MEMBERSHIP_PREPARING", "대상 사용자의 강의 참여 환경을 준비하고 있습니다.")
        }
        if (user.studentNum == null) {
            throw PublicApiException(HttpStatus.CONFLICT, "PROFILE_INCOMPLETE", "대상 사용자의 정보가 완료되지 않았습니다.")
        }
        AuthorizationUtil.validateUserAuthority(
            currentUser.role,
            currentUser.id,
            user.id,
            course.id,
            userCoursesRepository
        )
        if (redirectRequest.snapshot && currentUser.role != org.jbnu.jdevops.jcodeportallogin.entity.RoleType.ADMIN) {
            val viewerMembership = userCoursesRepository.findByUserIdAndCourseId(currentUser.id, course.id)
            if (viewerMembership?.lifecycleStatus != org.jbnu.jdevops.jcodeportallogin.entity.MembershipStatus.READY ||
                viewerMembership.role !in setOf(
                    org.jbnu.jdevops.jcodeportallogin.entity.RoleType.PROFESSOR,
                    org.jbnu.jdevops.jcodeportallogin.entity.RoleType.ASSISTANT
                )
            ) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "해당 강의의 Snapshot JCode 접근 권한이 없습니다.")
            }
        }

        val assignment = if (!redirectRequest.snapshot && redirectRequest.assignmentId != null) {
            assignmentRepository.findByIdAndCourseId(redirectRequest.assignmentId, course.id)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found in course") }
        } else null
        val ownerStudent = currentUser.id == user.id &&
            currentUser.role != org.jbnu.jdevops.jcodeportallogin.entity.RoleType.ADMIN &&
            targetMembership.role == org.jbnu.jdevops.jcodeportallogin.entity.RoleType.STUDENT
        if (currentUser.id != user.id && assignment == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "다른 학생의 JCode는 과제별 읽기 전용 검사로만 열 수 있습니다."
            )
        }
        if (assignment != null) {
            val accessible = if (ownerStudent) {
                workspaceAccessPolicy.isStudentAccessible(assignment)
            } else {
                assignment.lifecycleStatus == org.jbnu.jdevops.jcodeportallogin.entity.AssignmentLifecycleStatus.ACTIVE
            }
            if (!accessible) {
                throw PublicApiException(HttpStatus.CONFLICT, "ASSIGNMENT_NOT_ACCESSIBLE", "현재 접근할 수 없는 과제입니다.")
            }
        }

        val inspectorMode = assignment != null && !ownerStudent
        if (assignment != null && !inspectorMode &&
            course.workspaceScope == org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceScope.COURSE &&
            !workspaceOperationStore.assignmentMetadataReady(assignment.id, targetMembership.id)
        ) {
            // Keep the existing client's preparation retry contract during metadata updates.
            throw PublicApiException(HttpStatus.CONFLICT, "JCODE_POLICY_APPLYING", "과제 표시 정보를 갱신하고 있습니다. 잠시 후 자동으로 연결됩니다.")
        }
        val storedJcode = if (inspectorMode) {
            jCodeService.ensureInspector(currentEmail, user.email, course.id, assignment!!.id)
        } else if (!redirectRequest.snapshot && course.workspaceScope == org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceScope.ASSIGNMENT && assignment != null) {
            jCodeRepository.findFirstByUserIdAndCourseIdAndAssignmentIdAndKindAndLifecycleStatusNotOrderByIdDesc(
                user.id, course.id, assignment.id,
                org.jbnu.jdevops.jcodeportallogin.entity.JcodeKind.STANDARD,
                org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus.ARCHIVED
            )
        } else {
            jCodeRepository.findFirstByUserIdAndCourseIdAndSnapshotAndAssignmentIsNullAndLifecycleStatusNotOrderByIdDesc(
                user.id, course.id, redirectRequest.snapshot,
                org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus.ARCHIVED
            )
        }
            ?: throw PublicApiException(HttpStatus.CONFLICT, "JCODE_NOT_FOUND", "활성 JCode가 없습니다.")
        if (storedJcode.lifecycleStatus != org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus.READY) {
            val code = if (storedJcode.lifecycleStatus == org.jbnu.jdevops.jcodeportallogin.entity.JcodeLifecycleStatus.PROVISIONING) {
                "JCODE_PREPARING"
            } else {
                "JCODE_RECOVERY_REQUIRED"
            }
            throw PublicApiException(
                HttpStatus.CONFLICT,
                code,
                if (code == "JCODE_PREPARING") "JCode 실행 환경을 준비하고 있습니다." else "JCode 실행 환경 복구가 필요합니다."
            )
        }
        val plan = workspaceAccessPolicy.plan(storedJcode)
        if (
            storedJcode.observedRevision != storedJcode.desiredRevision ||
            storedJcode.observedMountHash != plan.mountHash
        ) {
            workspaceOperationStore.enqueueJcodeAccessReconcile(storedJcode.id, storedJcode.desiredRevision)
            throw PublicApiException(HttpStatus.CONFLICT, "JCODE_POLICY_APPLYING", "JCode 접근 정책을 적용하고 있습니다.")
        }
        jCodeRuntimeObserver.requireReady(storedJcode.id)
        val jcodeUrl = storedJcode.jcodeUrl
            ?: throw PublicApiException(HttpStatus.CONFLICT, "JCODE_PREPARING", "JCode 주소를 준비하고 있습니다.")

        val defaultExpiry = Instant.now().plusSeconds(6 * 3600)
        val expiresAt = if (inspectorMode) {
            storedJcode.expiresAt?.atZone(ZoneId.systemDefault())?.toInstant()
                ?: Instant.now().plusSeconds(30 * 60)
        } else if (ownerStudent && plan.validUntil != null) {
            minOf(defaultExpiry, plan.validUntil.atZone(ZoneId.systemDefault()).toInstant())
        } else {
            defaultExpiry
        }

        // 모든 검증이 끝난 뒤에만 짧은 수명의 Router 세션을 만든다.
        val uuid = redisService.storeUserProfile(
            user.email,
            user.studentNum.toString(),
            course.infrastructureKey,
            course.clss.toString(),
            redirectRequest.snapshot.toString(),
            storedJcode.id,
            currentUser.id,
            currentUser.email,
            targetMembership.id,
            assignment?.id,
            if (inspectorMode) "INSPECTOR" else "OWNER",
            storedJcode.observedRevision,
            plan.mountHash,
            expiresAt
        )
        val encodedUUID = URLEncoder.encode(uuid, StandardCharsets.UTF_8.toString()).replace("+", "%2B")

        // New sessions use only the JCode-id route below. Recreating the legacy
        // user/course route would let an old v2 profile bypass revision invalidation.
        redisService.storeJcodeRoute(
            storedJcode.id,
            jcodeUrl,
            storedJcode.observedRevision,
            plan.mountHash
        )
        storedJcode.lastRoutedAt = java.time.LocalDateTime.now()
        jCodeRepository.save(storedJcode)

        // 과제별 폴더 경로 결정
        var workspaceFile: String? = null
        val folderPath = if (!redirectRequest.snapshot && assignment != null) {
            if (inspectorMode || course.workspaceScope == org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceScope.ASSIGNMENT) {
                "/home/coder/project"
            } else {
                val assignmentWorkspaceFile = WorkspaceNaming.assignmentWorkspaceFile(assignment.name)
                workspaceFile = "/home/coder/project/.jcode/assignments/${assignment.workspaceKey}/$assignmentWorkspaceFile"
                "/home/coder/project/assignments/${assignment.workspaceKey}"
            }
        } else {
            if (!redirectRequest.snapshot && course.workspaceScope == org.jbnu.jdevops.jcodeportallogin.entity.WorkspaceScope.COURSE) {
                workspaceFile = "/home/coder/project/.jcode/${WorkspaceNaming.generalWorkspaceFile(user)}"
            }
            "/home/coder/project"
        }

        // Node.js 서버 URL에 인코딩된 UUID 파라미터만 포함하여 구성
        val targetParameter = if (workspaceFile != null) "workspace" else "folder"
        val targetPath = workspaceFile ?: folderPath
        val encodedTarget = URLEncoder.encode(targetPath, StandardCharsets.UTF_8.toString())
        val finalNodeJsUrl = "${routerUrl.trimEnd('/')}/session/$encodedUUID/?$targetParameter=$encodedTarget"

        // Keycloak Access Token을 HTTP-Only Secure 쿠키로 설정
        response.addCookie(jwtUtil.createJwtCookie("jcodeAt", token))

        // SPA에서 사용할 수 있도록 JSON으로 URL 반환
        return ResponseEntity.ok(mapOf("url" to finalNodeJsUrl))
    }
}
