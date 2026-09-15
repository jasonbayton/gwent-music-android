package org.bayton.gmc.di

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.bayton.gmc.BuildConfig
import org.bayton.gmc.data.api.ApiKeyProvider
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.auth.BearerGate
import org.bayton.gmc.data.auth.LoopbackAuth
import org.bayton.gmc.data.auth.ReauthenticationRequired
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import org.bayton.gmc.notify.NotificationScheduler
import org.bayton.gmc.notify.Notifier
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Enforces bearer freshness locally, regardless of whether the provider rejects an expired token. */
object NetworkModule {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    internal fun client(gate: BearerGate, key: () -> String?): OkHttpClient = OkHttpClient.Builder()
        // Do not forward the shared customer key to a redirect target.
        .followRedirects(false).followSslRedirects(false)
        // The Paritor Diary endpoint is slow over a wide date range; OkHttp's 10s default read timeout
        // was too tight and cut off legitimate responses. Modules now load independently, so a longer
        // per-request read window bounds a slow module without holding up the rest of the dashboard.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            var credentials = gate.credentials()
            fun request() = chain.request().newBuilder().apply {
                if (!gate.isCurrent(credentials)) throw ReauthenticationRequired()
                removeHeader("ApiKey")
                key()?.takeIf { it.isNotBlank() }?.let { header("ApiKey", it) }
                header("Authorization", "Bearer ${credentials.access}")
            }.build()
            var response = chain.proceed(request())
            if (!gate.isCurrent(credentials)) { response.close(); throw ReauthenticationRequired() }
            if (response.code == 401) {
                response.close()
                credentials = gate.rejected(credentials)
                response = chain.proceed(request())
                if (!gate.isCurrent(credentials)) { response.close(); throw ReauthenticationRequired() }
                if (response.code == 401) { response.close(); gate.reject(credentials) }
            }
            response
        }.build()

    fun api(context: Context, tokens: TokenStore, keys: ApiKeyProvider, auth: LoopbackAuth, session: Session): ParitorApi {
        val gate = BearerGate(tokens, auth::refreshBlocking, {
            session.clear()
            NotificationScheduler.cancelAll(context)
            Notifier.cancelAll(context)
        }, allowMock = BuildConfig.DEBUG && BuildConfig.USE_MOCK)
        return Retrofit.Builder().baseUrl(BuildConfig.API_BASE).client(client(gate, keys::get))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(ParitorApi::class.java)
    }
}
