# kotlinx.serialization: serializers are resolved reflectively through the generated Companion.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static <1>$$serializer $$serializer;
}

-keepclassmembers class **$$serializer {
    public <init>();
}

-if @kotlinx.serialization.Serializable class ** {
    static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    public static kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit reads a suspend method's return type out of its Continuation<...> generic signature. When
# the caller discards the response, R8 deletes the DTO and rewrites that signature to
# java.lang.Object, so the call fails at run time with "Unable to create converter for class
# java.lang.Object". The DTOs below are reachable only through reflection and must survive.
-keep class com.superstudent.core.model.*Response { *; }
-keep class com.superstudent.core.model.*Dto { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Conscrypt / BouncyCastle are optional OkHttp TLS providers we do not ship.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# --- Room (ZLQ-115 release hardening) ---
# Room.databaseBuilder(...) reflectively instantiates the generated SsDatabase_Impl and reads the
# @Database/@Entity/@Dao metadata at runtime, so the RoomDatabase subclass and its no-arg
# constructor must survive shrinking. The generated *_Impl is a RoomDatabase subclass, so this rule
# keeps it too. androidx.room.paging is referenced by room-runtime but never shipped here.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.superstudent.core.database.**_Impl { *; }
-dontwarn androidx.room.paging.**

# --- Retrofit service interface (ZLQ-115) ---
# Retrofit builds QcaApi through java.lang.reflect.Proxy and reads the @GET/@POST/@Query/@Path/...
# method annotations plus each suspend method's Continuation<...> return type reflectively at call
# time. Keep the interface (obfuscation allowed) and every @retrofit2.http-annotated method so the
# generated proxy still resolves. Runtime annotation attributes must be retained for that.
-keep,allowobfuscation interface com.superstudent.core.network.QcaApi { *; }
-keepclasseswithmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# --- OkHttp / OkIO / coroutines optional-platform references (ZLQ-115) ---
# OkHttp 5 reflectively probes optional platform TLS providers and animal-sniffer annotations that
# are absent on Android; kotlinx.coroutines references a DebugProbes hook only present in tests.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okio.**
-dontwarn kotlinx.coroutines.**
-dontwarn coil.**

# --- Compose (ZLQ-115) ---
# Compose ships its own consumer R8 rules inside the androidx.compose.* artifacts and needs no
# app-side keep; the annotation/signature attributes it reflects on are already retained by the
# -keepattributes line at the top of this file. compose-ui-tooling is a debugImplementation only and
# is never present in the release graph, so its references are silenced rather than kept.
-dontwarn androidx.compose.ui.tooling.**
