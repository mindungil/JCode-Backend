package org.jbnu.jdevops.jcodeportallogin.dto.course

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StringDeserializer
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

class CourseJoinDto (
    // Enrollment codes are opaque credentials, not HTML. Escaping changes the hash input.
    @field:JsonDeserialize(using = StringDeserializer::class)
    @field:NotBlank(message = "{course.key.required}")
    @field:Size(max = 100, message = "{course.key.size}")
    val courseKey: String
)
