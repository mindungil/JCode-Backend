package org.jbnu.jdevops.jcodeportallogin.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Decides whether closed assignments are shown read-only in course workspaces. */
fun interface ClosedAssignmentAccess {
    fun readOnlyEnabled(): Boolean
}

@Component
class GeneratorClosedAssignmentAccess(
    private val generatorContractVerifier: GeneratorContractVerifier,
    @Value("\${workspace.closed-assignment-read-only.enabled:false}") private val enabled: Boolean
) : ClosedAssignmentAccess {
    // An older Generator would silently ignore read-only mounts, so the Backend flag
    // alone is not enough: the deployed Generator must advertise the capability.
    override fun readOnlyEnabled(): Boolean =
        enabled && generatorContractVerifier.supports(GENERATOR_FEATURE)

    companion object {
        const val GENERATOR_FEATURE = "closed-assignment-read-only-mounts"
    }
}
