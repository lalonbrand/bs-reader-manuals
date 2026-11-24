package com.example

import com.example.data.firebase.ExamStaffRole
import com.example.data.firebase.StaffAuthManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffAuthManagerTest {

    @Test
    fun `normalizeUsernameOrEmail appends default domain for plain usernames`() {
        assertEquals("mrlalon@bsreader.local", StaffAuthManager.normalizeUsernameOrEmail("mrlalon"))
        assertEquals("mrlalon@bsreader.local", StaffAuthManager.normalizeUsernameOrEmail("  MrLalon  "))
        assertEquals("coord1@bsreader.local", StaffAuthManager.normalizeUsernameOrEmail("coord1"))
        assertEquals("trainer@company.com", StaffAuthManager.normalizeUsernameOrEmail("Trainer@Company.com"))
        assertEquals("qa.lead@gmail.com", StaffAuthManager.normalizeUsernameOrEmail("  QA.Lead@Gmail.com "))
        assertEquals("", StaffAuthManager.normalizeUsernameOrEmail("   "))
    }

    @Test
    fun `isValidStaffEmail accepts standard and local staff emails and rejects malformed ones`() {
        assertTrue(StaffAuthManager.isValidStaffEmail("mrlalon@bsreader.local"))
        assertTrue(StaffAuthManager.isValidStaffEmail("user.name+qa@gmail.com"))
        assertTrue(StaffAuthManager.isValidStaffEmail("staff@outlook.com"))
        assertTrue(StaffAuthManager.isValidStaffEmail("team@yahoo.com"))
        assertFalse(StaffAuthManager.isValidStaffEmail("mrlalon"))
        assertFalse(StaffAuthManager.isValidStaffEmail("bad email@gmail.com"))
        assertFalse(StaffAuthManager.isValidStaffEmail("@gmail.com"))
        assertFalse(StaffAuthManager.isValidStaffEmail("user@localhost"))
    }

    @Test
    fun `roleForEmail grants Super Admin to root or delegated emails and Coordinator to others`() {
        assertEquals(ExamStaffRole.SUPER_ADMIN, StaffAuthManager.roleForEmail("mrlalon@bsreader.local"))
        assertEquals(ExamStaffRole.SUPER_ADMIN, StaffAuthManager.roleForEmail("  MRLALON@BSREADER.LOCAL "))
        assertEquals(ExamStaffRole.COORDINATOR, StaffAuthManager.roleForEmail("coord1@bsreader.local"))
        assertEquals(ExamStaffRole.COORDINATOR, StaffAuthManager.roleForEmail("qa.trainer@gmail.com"))
        assertEquals(
            ExamStaffRole.SUPER_ADMIN,
            StaffAuthManager.roleForEmail("qa.trainer@gmail.com", isDelegatedSuperAdmin = true)
        )
        assertEquals(ExamStaffRole.NONE, StaffAuthManager.roleForEmail("", isDelegatedSuperAdmin = true))
        assertEquals(ExamStaffRole.NONE, StaffAuthManager.roleForEmail(null))
        assertTrue(StaffAuthManager.isRootSuperAdminEmail("MRLALON@bsreader.local"))
        assertFalse(StaffAuthManager.isRootSuperAdminEmail("qa.trainer@gmail.com"))
    }
}
