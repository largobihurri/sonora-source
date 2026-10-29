package io.sonora.core.api

import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.CallerVerdict.Allowed
import io.sonora.core.api.CallerVerdict.Denied
import org.junit.Test

class CallerPolicyTest {

    private val policy = CallerPolicy(ownUid = OWN_UID)

    /** Counts how often the permission was looked up: the lookup is an IPC to the package manager. */
    private class Permission(private val held: Boolean) {
        var lookups = 0
        fun holds(): Boolean {
            lookups++
            return held
        }
    }

    private fun check(uid: Int, switchOn: Boolean, holdsPermission: Boolean = false) =
        policy.check(uid, switchOn, Permission(holdsPermission)::holds)

    @Test
    fun `Sonora's own uid is allowed whether the switch is on or off and without the permission`() {
        assertThat(check(OWN_UID, switchOn = false)).isEqualTo(Allowed)
        assertThat(check(OWN_UID, switchOn = true)).isEqualTo(Allowed)
    }

    @Test
    fun `an own-uid call never looks the permission up`() {
        val permission = Permission(held = false)

        policy.check(OWN_UID, switchOn = false, holdsPermission = permission::holds)

        assertThat(permission.lookups).isEqualTo(0)
    }

    @Test
    fun `every other caller is denied while the switch is off, the shell and root included`() {
        for (uid in listOf(SHELL_UID, ROOT_UID, OTHER_APP_UID)) {
            assertThat(check(uid, switchOn = false, holdsPermission = true)).isEqualTo(Denied(DenialReason.SWITCH_OFF))
        }
    }

    @Test
    fun `the shell is allowed once the switch is on, without the permission`() {
        assertThat(check(SHELL_UID, switchOn = true)).isEqualTo(Allowed)
        assertThat(CallerPolicy.SHELL_UID).isEqualTo(2000)
    }

    @Test
    fun `root is allowed once the switch is on, without the permission`() {
        assertThat(check(ROOT_UID, switchOn = true)).isEqualTo(Allowed)
        assertThat(CallerPolicy.ROOT_UID).isEqualTo(0)
    }

    @Test
    fun `the shell and root never look the permission up`() {
        val permission = Permission(held = false)

        policy.check(SHELL_UID, switchOn = true, holdsPermission = permission::holds)
        policy.check(ROOT_UID, switchOn = true, holdsPermission = permission::holds)

        assertThat(permission.lookups).isEqualTo(0)
    }

    @Test
    fun `an app holding the permission is allowed once the switch is on`() {
        assertThat(check(OTHER_APP_UID, switchOn = true, holdsPermission = true)).isEqualTo(Allowed)
    }

    @Test
    fun `an app without the permission is denied even with the switch on`() {
        assertThat(check(OTHER_APP_UID, switchOn = true, holdsPermission = false))
            .isEqualTo(Denied(DenialReason.PERMISSION_MISSING))
    }

    @Test
    fun `the permission is not looked up while the switch is off`() {
        val permission = Permission(held = true)

        policy.check(OTHER_APP_UID, switchOn = false, holdsPermission = permission::holds)

        assertThat(permission.lookups).isEqualTo(0)
    }

    @Test
    fun `a system uid other than the shell and root gets no special treatment`() {
        val system = 1000
        assertThat(check(system, switchOn = true, holdsPermission = false)).isEqualTo(Denied(DenialReason.PERMISSION_MISSING))
        assertThat(check(system, switchOn = true, holdsPermission = true)).isEqualTo(Allowed)
    }

    @Test
    fun `a policy whose own uid is the shell's still treats the shell as its own`() {
        assertThat(CallerPolicy(ownUid = SHELL_UID).check(SHELL_UID, switchOn = false) { false }).isEqualTo(Allowed)
    }

    @Test
    fun `a denial says why in English without naming the caller`() {
        assertThat(DenialReason.SWITCH_OFF.message).contains("External control")
        assertThat(DenialReason.PERMISSION_MISSING.message).contains(EXTERNAL_CONTROL_PERMISSION)
        assertThat(EXTERNAL_CONTROL_PERMISSION).isEqualTo("io.sonora.permission.EXTERNAL_CONTROL")
    }

    private companion object {
        const val OWN_UID = 10_123
        const val OTHER_APP_UID = 10_456
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
