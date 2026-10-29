package io.sonora.core.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiErrorTest {

    @Test
    fun `the playback service being unreachable has its own stable code`() {
        assertThat(ApiError.PlaybackUnavailable.code).isEqualTo("playback_unavailable")
        assertThat(ApiError.PlaybackUnavailable.argument).isNull()
        assertThat(ApiError.PlaybackUnavailable.message).contains("playback")
    }

    @Test
    fun `a call that outlasts its time budget has its own stable code`() {
        assertThat(ApiError.Timeout.code).isEqualTo("timeout")
        assertThat(ApiError.Timeout.argument).isNull()
        assertThat(ApiError.Timeout.message).contains("time")
    }

    @Test
    fun `every code is a distinct lower case word`() {
        val codes = listOf(
            ApiError.UnknownMethod("x"), ApiError.UnknownQuery, ApiError.MissingArgument("x"),
            ApiError.BadArgumentType("x", "integer"), ApiError.ArgumentOutOfRange("x", "1"),
            ApiError.BadArgumentValue("x", "y"), ApiError.LocationRejected("x"), ApiError.NotFound("track", 1),
            ApiError.NothingToDo("none"), ApiError.Failed, ApiError.PlaybackUnavailable, ApiError.Timeout,
        ).map { it.code }

        assertThat(codes).containsNoDuplicates()
        assertThat(codes.all { code -> code.all { it.isLowerCase() || it == '_' } }).isTrue()
    }
}
