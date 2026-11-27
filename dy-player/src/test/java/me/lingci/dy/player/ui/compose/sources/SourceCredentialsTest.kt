package me.lingci.dy.player.ui.compose.sources

import me.lingci.lib.base.storage.smb.SmbAuthToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCredentialsTest {

    @Test
    fun smb_blankUsername_staysEmpty_withDomain() {
        // 空用户名 = 匿名访问；带上域名也必须保持空，不能变成 DOMAIN\guest
        assertEquals("", SourceCredentials.smbUsername("", "WORKGROUP"))
        assertEquals("", SourceCredentials.smbUsername("   ", "WORKGROUP"))
        assertEquals("", SourceCredentials.smbUsername("", ""))
    }

    @Test
    fun smb_userWithDomain_isJoined() {
        assertEquals("WORKGROUP\\alice", SourceCredentials.smbUsername("alice", "WORKGROUP"))
        assertEquals("WORKGROUP\\alice", SourceCredentials.smbUsername(" alice ", " WORKGROUP "))
        assertEquals("WORKGROUP\\alice", SourceCredentials.smbUsername("WORKGROUP\\alice", "WORKGROUP"))
        assertEquals("WORKGROUP;alice", SourceCredentials.smbUsername("WORKGROUP;alice", "WORKGROUP"))
    }

    @Test
    fun smb_userWithoutDomain_staysPlain() {
        assertEquals("alice", SourceCredentials.smbUsername("alice", ""))
        assertEquals("alice", SourceCredentials.smbUsername("alice", "   "))
        // 用户自己写了域时原样透传，由存储层拆分，不重复拼前缀
        assertEquals("WORKGROUP\\alice", SourceCredentials.smbUsername("WORKGROUP\\alice", ""))
    }

    @Test
    fun smb_emptyPassword_staysEmpty_andWhitespacePreserved() {
        assertEquals("", SourceCredentials.smbPassword(""))
        assertEquals("   ", SourceCredentials.smbPassword("   "))
        assertEquals(" secret ", SourceCredentials.smbPassword(" secret "))
    }

    @Test
    fun smbToken_preservesWhitespacePassword() {
        assertEquals("   ", SmbAuthToken.normalize("alice", "   ").password)
        assertEquals(" secret ", SmbAuthToken.normalize("alice", " secret ").password)
    }

    @Test
    fun smb_windowsGuest_usesStableUsername() {
        assertEquals("Guest", SourceCredentials.smbWindowsGuestUsername())
    }

    @Test
    fun smb_windowsGuest_isDetectedOnlyWithEmptyPassword() {
        assertTrue(SourceCredentials.isSmbWindowsGuest("Guest", ""))
        assertTrue(SourceCredentials.isSmbWindowsGuest(" guest ", ""))
        assertFalse(SourceCredentials.isSmbWindowsGuest("", ""))
        assertFalse(SourceCredentials.isSmbWindowsGuest("Guest", "secret"))
        assertFalse(SourceCredentials.isSmbWindowsGuest("WORKGROUP\\Guest", ""))
    }

    @Test
    fun webdav_blankCredentials_fallBackToGuest() {
        assertEquals("guest", SourceCredentials.webdavUsername(""))
        assertEquals("guest", SourceCredentials.webdavPassword(" "))
        assertEquals("alice", SourceCredentials.webdavUsername(" alice "))
        assertEquals("secret", SourceCredentials.webdavPassword("secret"))
    }
}
