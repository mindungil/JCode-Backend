package org.jbnu.jdevops.jcodeportallogin.service

/** Why a course policy revision changed; shown to students whose open IDE must restart. */
enum class PolicyChangeReason {
    ASSIGNMENT_CREATED,
    ASSIGNMENT_UPDATED,
    ASSIGNMENT_DELETED,
    ASSIGNMENT_OPENED,
    ASSIGNMENT_CLOSED,
    ASSIGNMENT_REOPENED,
    STARTER_DISTRIBUTED,
    MEMBER_ROLE_CHANGED,
    PROFILE_CHANGED,
    POLICY_CHANGED
}
