# kotlinx.serialization + Retrofit models
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class org.bayton.gmc.data.model.** { *; }
-keepclassmembers class org.bayton.gmc.data.model.** { *; }

# WorkManager instantiates workers reflectively by class name; keep them and their constructor.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class org.bayton.gmc.notify.** { *; }

# Tink / Jetpack Security (EncryptedSharedPreferences) uses reflection internally.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }
