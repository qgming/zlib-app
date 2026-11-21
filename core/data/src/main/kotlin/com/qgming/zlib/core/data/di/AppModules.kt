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
import com.qgming.zlib.core.network.EapiClient
import com.qgming.zlib.core.network.EapiJson
import com.qgming.zlib.core.network.EapiRedirectGuardInterceptor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import android.content.Context
import androidx.room.Room
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

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
        .followSslRedirects(true)
        .addNetworkInterceptor(EapiRedirectGuardInterceptor(isAllowedUrl = { url -> url.isHttps && registry.isAllowed(url.host) }))
        .addNetworkInterceptor(CredentialStripInterceptor(isAllowedUrl = { url -> registry.isAllowed(url.host) }))
        .build()

    @Provides
    @Singleton
    fun domainProbe(
        client: OkHttpClient,
        registry: AllowedHostRegistry,
        dispatchers: AppDispatchers,
    ): DomainProbe = DomainProbe(
        client = client,
        dispatchers = dispatchers,
        isAllowedHost = registry::isAllowed,
    )

    @Provides
    @Singleton
    fun eapiClient(
        client: OkHttpClient,
        json: Json,
        domainRepository: DomainRepository,
        sessionManager: SessionManager,
        dispatchers: AppDispatchers,
    ): EapiClient = EapiClient(
        client = client,
        json = json,
        baseUrlProvider = { domainRepository.currentHostOrNull()?.let(DomainPolicy::baseUrl) },
        sessionProvider = { sessionManager.current() },
        dispatchers = dispatchers,
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
