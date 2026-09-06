package org.jbnu.jdevops.jcodeportallogin.repo

import org.jbnu.jdevops.jcodeportallogin.entity.Course
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<Course, Long> {
    fun courseKey(courseKey: String): MutableList<Course>
    fun findByInfrastructureKeyAndClss(infrastructureKey: String, clss: Int): List<Course>
    fun existsByNamespaceKey(namespaceKey: String): Boolean
}
