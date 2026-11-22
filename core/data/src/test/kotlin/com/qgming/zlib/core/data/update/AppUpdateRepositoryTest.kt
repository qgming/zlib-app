package com.qgming.zlib.core.data.update

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.UpdateCheckResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class AppUpdateRepositoryTest {
    private fun repository(tag: String?) = AppUpdateRepository {
        AppResult.Success(tag?.let { AppRelease(it, "", "https://github.com/qgming/zlib-app/releases") })
    }

    @Test fun `checks distinguish update current no release and unknown tag`() = runTest {
        assertIs<UpdateCheckResult.Available>(assertIs<AppResult.Success<UpdateCheckResult>>(repository("v0.10.0").check("0.9.0")).value)
        for (tag in listOf("v0.1.0", "v0.0.9")) {
            assertIs<UpdateCheckResult.Current>(assertIs<AppResult.Success<UpdateCheckResult>>(repository(tag).check("0.1.0")).value)
        }
        assertEquals(UpdateCheckResult.NoRelease, assertIs<AppResult.Success<UpdateCheckResult>>(repository(null).check("0.1.0")).value)
        assertIs<UpdateCheckResult.Uncomparable>(assertIs<AppResult.Success<UpdateCheckResult>>(repository("nightly").check("0.1.0")).value)
    }

    @Test fun `transport failure is preserved`() = runTest {
        val error = AppResult.Failure(AppError.Network(IOException("Offline")))
        assertEquals(error, AppUpdateRepository { error }.check("0.1.0"))
    }
}
