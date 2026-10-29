package io.sonora.core.playback.api

import android.net.Uri
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiResult
import io.sonora.core.api.CommandParser
import io.sonora.core.api.ParseResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two-way adaptation between a `call` Bundle and the core's plain maps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApiBundlesTest {

    @Test
    fun `no extras read as an empty map`() {
        assertThat(ApiBundles.toMap(null)).isEmpty()
        assertThat(ApiBundles.toMap(Bundle())).isEmpty()
    }

    @Test
    fun `plain values keep their kind`() {
        val extras = Bundle().apply {
            putString("text", "Love")
            putInt("int", 3)
            putLong("long", 4_000_000_000L)
            putDouble("double", 1.5)
            putBoolean("flag", true)
        }

        assertThat(ApiBundles.toMap(extras)).containsExactly(
            "text", "Love", "int", 3, "long", 4_000_000_000L, "double", 1.5, "flag", true,
        )
    }

    @Test
    fun `a float is widened so a speed of 1_25 reads as a number`() {
        val map = ApiBundles.toMap(Bundle().apply { putFloat("speed", 1.25f) })

        assertThat((map["speed"] as Number).toDouble()).isEqualTo(1.25)
    }

    @Test
    fun `id arrays and lists reach the core as lists`() {
        val extras = Bundle().apply {
            putLongArray("longs", longArrayOf(1, 2))
            putIntArray("ints", intArrayOf(3, 4))
            putStringArray("strings", arrayOf("5", "6"))
            putIntegerArrayList("list", arrayListOf(7, 8))
            putStringArrayList("texts", arrayListOf("9"))
        }

        val map = ApiBundles.toMap(extras)

        assertThat(map["longs"]).isEqualTo(listOf(1L, 2L))
        assertThat(map["ints"]).isEqualTo(listOf(3, 4))
        assertThat(map["strings"]).isEqualTo(listOf("5", "6"))
        assertThat(map["list"]).isEqualTo(listOf(7, 8))
        assertThat(map["texts"]).isEqualTo(listOf("9"))
    }

    @Test
    fun `a value of any other kind cannot be mistaken for a plain value`() {
        val extras = Bundle().apply {
            putParcelable("uri", Uri.parse("content://media/external/audio/1"))
            putBundle("nested", Bundle())
        }

        val map = ApiBundles.toMap(extras)

        assertThat(map.keys).containsExactly("uri", "nested")
        assertThat(map["uri"]).isNotInstanceOf(String::class.java)
        assertThat(map["uri"]).isNotInstanceOf(Number::class.java)
        assertThat(map["nested"]).isNotInstanceOf(String::class.java)
    }

    @Test
    fun `an unusable value is a typed error only when a method reads it`() {
        val extras = Bundle().apply {
            putParcelable("uri", Uri.parse("content://media/external/audio/1"))
            putLong("position_ms", 5)
        }

        val unused = CommandParser.parse("seek", ApiBundles.toMap(extras))
        assertThat(unused).isInstanceOf(ParseResult.Parsed::class.java)

        val read = CommandParser.parse("seek", ApiBundles.toMap(Bundle().apply { putParcelable("position_ms", Uri.EMPTY) }))
        assertThat((read as ParseResult.Rejected).error).isEqualTo(ApiError.BadArgumentType("position_ms", "integer"))
    }

    @Test
    fun `a string that looks like a location still reaches the core to be rejected`() {
        val map = ApiBundles.toMap(Bundle().apply { putString("q", "content://media/external/audio/1") })

        assertThat((CommandParser.parse("play_search", map) as ParseResult.Rejected).error)
            .isEqualTo(ApiError.LocationRejected("q"))
    }

    @Test
    fun `an ok result carries its payload with ok true and the api version`() {
        val bundle = ApiBundles.toBundle(ApiResult.Ok(mapOf("count" to 3, "seconds" to 4L, "speed" to 1.25, "flag" to true, "name" to "x")))

        assertThat(bundle.getBoolean("ok")).isTrue()
        assertThat(bundle.getInt("api_version")).isEqualTo(1)
        assertThat(bundle.getInt("count")).isEqualTo(3)
        assertThat(bundle.getLong("seconds")).isEqualTo(4L)
        assertThat(bundle.getDouble("speed")).isEqualTo(1.25)
        assertThat(bundle.getBoolean("flag")).isTrue()
        assertThat(bundle.getString("name")).isEqualTo("x")
        assertThat(bundle.containsKey("error")).isFalse()
    }

    @Test
    fun `an ok result without a payload is just ok`() {
        val bundle = ApiBundles.toBundle(ApiResult.Ok())

        assertThat(bundle.keySet()).containsExactly("ok", "api_version")
    }

    @Test
    fun `a failure carries ok false, the stable code, the message and the argument at fault`() {
        val bundle = ApiBundles.toBundle(ApiResult.Failed(ApiError.MissingArgument("ids")))

        assertThat(bundle.getBoolean("ok", true)).isFalse()
        assertThat(bundle.getString("error")).isEqualTo("missing_argument")
        assertThat(bundle.getString("error_message")).isEqualTo("Missing argument: ids")
        assertThat(bundle.getString("error_argument")).isEqualTo("ids")
        assertThat(bundle.getInt("api_version")).isEqualTo(1)
    }

    @Test
    fun `a failure about no argument has no error_argument`() {
        val bundle = ApiBundles.toBundle(ApiResult.Failed(ApiError.Timeout))

        assertThat(bundle.getString("error")).isEqualTo("timeout")
        assertThat(bundle.containsKey("error_argument")).isFalse()
    }

    @Test
    fun `a payload key cannot overwrite the result keys`() {
        val bundle = ApiBundles.toBundle(ApiResult.Ok(mapOf("ok" to "no", "api_version" to 99, "error" to "x")))

        assertThat(bundle.getBoolean("ok")).isTrue()
        assertThat(bundle.getInt("api_version")).isEqualTo(1)
        assertThat(bundle.containsKey("error")).isFalse()
    }

    @Test
    fun `a query error becomes an exception that starts with its code`() {
        val bad = ApiErrors.exceptionFor(ApiError.ArgumentOutOfRange("limit", "1..200"))
        val missing = ApiErrors.exceptionFor(ApiError.NotFound("album", 9))
        val broken = ApiErrors.exceptionFor(ApiError.Failed)
        val slow = ApiErrors.exceptionFor(ApiError.Timeout)

        assertThat(bad).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(bad).hasMessageThat().startsWith("argument_out_of_range: ")
        assertThat(missing).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(missing).hasMessageThat().isEqualTo("not_found: No album with id 9")
        assertThat(broken).isInstanceOf(IllegalStateException::class.java)
        assertThat(broken).hasMessageThat().startsWith("failed: ")
        assertThat(slow).isInstanceOf(IllegalStateException::class.java)
        assertThat(ApiErrors.exceptionFor(ApiError.PlaybackUnavailable)).isInstanceOf(IllegalStateException::class.java)
        assertThat(ApiErrors.exceptionFor(ApiError.NothingToDo("x"))).isInstanceOf(IllegalStateException::class.java)
    }
}
