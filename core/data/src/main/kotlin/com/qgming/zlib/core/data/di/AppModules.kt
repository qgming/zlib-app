package com.qgming.zlib.core.data.di

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.DefaultAppDispatchers
import com.qgming.zlib.core.data.crypto.KeystoreSecretCipher
import com.qgming.zlib.core.data.crypto.SecretCipher
import com.qgming.zlib.core.data.domain.AllowedHostRegistry
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.DataStoreSessionStore
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.settings.DataStoreSettingsStore
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.network.CredentialStripInterceptor
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.DomainProbe
import com.qgming.zlib.core.network.DomainVerificationCookies
import com.qgming.zlib.core.network.EapiClient
import com.qgming.zlib.core.network.EapiJson
import com.qgming.zlib.core.network.GitHubReleaseClient
import com.qgming.zlib.core.network.PapiBooklistClient
import com.qgming.zlib.core.network.PapiCommentsClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.Room
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun domainVerificationCookies(@ApplicationContext context: Context, registry: AllowedHostRegistry): DomainVerificationCookies {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val monitoring = AtomicBoolean(false)
        val cookies = DomainVerificationCookies(registry::isAllowed, {
            if (monitoring.get()) connectivity?.activeNetwork?.networkHandle else null
        })
        if (connectivity != null) {
            monitoring.set(runCatching {
                connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = cookies.clear()

                    override fun onLost(network: Network) = cookies.clear()
                })
                true
            }.getOrDefault(false))
        }
        return cookies
    }

    @Provides
    @Singleton
    fun gitHubReleaseClient(@ApplicationContext context: Context): GitHubReleaseClient {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return GitHubReleaseClient { connectivity?.activeNetwork?.networkHandle }
    }

    @Provides
    @Singleton
    fun papiBooklistClient(client: OkHttpClient, registry: AllowedHostRegistry): PapiBooklistClient =
        PapiBooklistClient(client, registry::isAllowed)

    @Provides
    @Singleton
    fun papiCommentsClient(client: OkHttpClient, registry: AllowedHostRegistry): PapiCommentsClient =
        PapiCommentsClient(client, registry::isAllowed)

    @Provides
    @Singleton
    fun dispatchers(): AppDispatchers = DefaultAppDispatchers()

    @Provides
    @Singleton
    fun json(): Json = EapiJson.instance

    @Provides
    @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ZlibDatabase =
        Room.databaseBuilder(context, ZlibDatabase::class.java, ZlibDatabase.NAME).build()

    @Provides
    fun downloadDao(database: ZlibDatabase) = database.downloadDao()

    @Provides
    fun bookDao(database: ZlibDatabase) = database.bookDao()

    @Provides
    @Singleton
    fun okHttpClient(registry: AllowedHostRegistry): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .addNetworkInterceptor(CredentialStripInterceptor(isAllowedUrl = { url -> registry.isAllowed(url.host) }))
        .build()

    @Provides
    @Singleton
    fun domainProbe(
        client: OkHttpClient,
        registry: AllowedHostRegistry,
        dispatchers: AppDispatchers,
        verificationCookies: DomainVerificationCookies,
    ): DomainProbe = DomainProbe(
        client = client,
        dispatchers = dispatchers,
        isAllowedHost = registry::isProbeAllowed,
        isTrustedHost = { registry.isAllowed(it) || DomainPolicy.isCatalogHost(it) },
        verificationCookieProvider = verificationCookies::cookieHeaderFor,
    )

    @Provides
    @Singleton
    fun eapiClient(
        client: OkHttpClient,
        json: Json,
        domainRepository: DomainRepository,
        registry: AllowedHostRegistry,
        sessionManager: SessionManager,
        dispatchers: AppDispatchers,
        verificationCookies: DomainVerificationCookies,
    ): EapiClient = EapiClient(
        client = client,
        json = json,
        baseUrlProvider = { domainRepository.currentHostOrNull()?.let(DomainPolicy::baseUrl) },
        sessionProvider = { sessionManager.current() },
        dispatchers = dispatchers,
        isAllowedUrl = { url -> DomainPolicy.isStandardHttps(url) && registry.isAllowed(url.host) },
        verificationCookieProvider = verificationCookies::cookieHeaderFor,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {

    @Binds
    @Singleton
    abstract fun sessionStore(impl: DataStoreSessionStore): SessionStore

    @Binds
    @Singleton
    abstract fun settingsStore(impl: DataStoreSettingsStore): SettingsStore
}
