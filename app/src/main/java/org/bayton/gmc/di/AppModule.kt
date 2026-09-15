package org.bayton.gmc.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bayton.gmc.data.api.ApiKeyProvider
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.auth.LoopbackAuth
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import javax.inject.Qualifier
import javax.inject.Singleton

/** A process-lifetime scope for work that must outlive the screen that started it (e.g. disconnect). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @AppScope fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Provides @Singleton fun tokenStore(@ApplicationContext c: Context) = TokenStore(c)
    @Provides @Singleton fun apiKeys(@ApplicationContext c: Context) = ApiKeyProvider(c)
    @Provides @Singleton fun session(@ApplicationContext c: Context) = Session(c)
    @Provides @Singleton fun loopbackAuth(@ApplicationContext c: Context) = LoopbackAuth(c)
    @Provides @Singleton fun api(@ApplicationContext c: Context, t: TokenStore, k: ApiKeyProvider, auth: LoopbackAuth, s: Session): ParitorApi =
        NetworkModule.api(c, t, k, auth, s)
}
