package com.moyue.reader.feature.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun release(code:Long=21,minSdk:Int=26,url:String="https://github.com/fshfish88-lab/moyue/releases/download/v1.5.4/Moyue-1.5.4.apk",hash:String="a".repeat(64),size:Long=16_000_000)=UpdateRelease("1.5.4",code,minSdk,url,hash,size,"修复目录")
    @Test fun onlyHigherCompatibleVersionIsOffered() {
        assertEquals(UpdateDecision.AVAILABLE,release().decision(20,26))
        assertEquals(UpdateDecision.CURRENT,release(20).decision(20,36))
        assertEquals(UpdateDecision.CURRENT,release(19).decision(20,36))
        assertEquals(UpdateDecision.INCOMPATIBLE,release(minSdk=29).decision(20,26))
    }
    @Test fun rejectsUntrustedAndAmbiguousDownloadAddresses() {
        for(url in listOf("http://github.com/fshfish88-lab/moyue/releases/download/v1.5.4/Moyue-1.5.4.apk","https://github.com.evil.test/fshfish88-lab/moyue/releases/download/v1.5.4/Moyue-1.5.4.apk","https://github.com/other/repo/releases/download/v1.5.4/Moyue-1.5.4.apk","https://user@github.com/fshfish88-lab/moyue/releases/download/v1.5.4/Moyue-1.5.4.apk","https://github.com/fshfish88-lab/moyue/releases/download/v1.5.4/Moyue-1.5.4.apk?token=abc","https://github.com/fshfish88-lab/moyue/releases/download/v1.5.4/../Moyue-1.5.4.apk")) {
            assertThrows(IllegalArgumentException::class.java){release(url=url)}
        }
    }
    @Test fun rejectsMissingDigestOversizeAndInvalidVersion() {
        assertThrows(IllegalArgumentException::class.java){release(hash="")}
        assertThrows(IllegalArgumentException::class.java){release(size=0)}
        assertThrows(IllegalArgumentException::class.java){release(size=151L*1024*1024)}
        assertThrows(IllegalArgumentException::class.java){release(code=-1)}
    }
    @Test fun automaticChecksRespectSwitchSuccessIntervalAndFailureBackoff() {
        val now=10*UpdatePolicy.DAY
        assertTrue(UpdatePolicy.shouldAutoCheck(true,0,0,now))
        assertFalse(UpdatePolicy.shouldAutoCheck(false,0,0,now))
        assertFalse(UpdatePolicy.shouldAutoCheck(true,now-UpdatePolicy.HOUR,now-UpdatePolicy.DAY+1,now))
        assertFalse(UpdatePolicy.shouldAutoCheck(true,now-1,0,now))
        assertTrue(UpdatePolicy.shouldAutoCheck(true,now-UpdatePolicy.HOUR,now-UpdatePolicy.DAY,now))
        assertTrue(UpdatePolicy.shouldAutoCheck(true,now+1,now+1,now))
    }
}
