package org.jbnu.jdevops.jcodeportallogin.dto.course

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CourseDto(
    val courseId: Long?,

    @field:NotBlank(message = "{course.name.required}")
    @field:Size(max = 100, message = "{course.name.size}")
    val name: String,

    @field:NotBlank(message = "{course.code.required}")
    @field:Size(max = 20, message = "{course.code.size}")
    val code: String,

    @field:NotBlank(message = "{professor.name.required}")
    @field:Size(max = 50, message = "{professor.name.size}")
    val professor: String,

    val year: Int,
    val term: Int,
    val clss: Int,
    val vnc: Boolean,
    val hwCount: Int = 10,
    val pracEnabled: Boolean = false,
    val pracCount: Int = 0,

    @field:NotBlank(message = "{course.key.required}")
    @field:Size(max = 100, message = "{course.key.size}")
    val courseKey: String? = "hidden"
)
