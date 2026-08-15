package com.audiobookshelf.app.models

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTest {
  private val mapper = jacksonObjectMapper()

  @Test
  fun `unknown server fields are ignored during deserialization`() {
    val json =
            """{"id":"u1","username":"jane","mediaProgress":[],"permissions":{"download":true}}"""

    val user = mapper.readValue<User>(json)

    assertEquals("u1", user.id)
    assertEquals("jane", user.username)
    assertTrue(user.mediaProgress.isEmpty())
  }

  @Test(expected = com.fasterxml.jackson.databind.exc.MismatchedInputException::class)
  fun `missing required username throws`() {
    val json = """{"id":"u1","mediaProgress":[]}"""

    mapper.readValue<User>(json)
  }
}
