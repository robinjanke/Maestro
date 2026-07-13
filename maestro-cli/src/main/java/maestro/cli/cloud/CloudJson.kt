package maestro.cli.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

internal fun cloudObjectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
