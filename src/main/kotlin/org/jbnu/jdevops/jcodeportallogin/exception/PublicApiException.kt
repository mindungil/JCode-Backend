package org.jbnu.jdevops.jcodeportallogin.exception

import org.springframework.http.HttpStatusCode

class PublicApiException(
    val status: HttpStatusCode,
    val errorCode: String,
    override val message: String
) : RuntimeException(message)
