# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 保留 Shizuku 相关类
-keep class rikka.shizuku.** { *; }

# 保留 Shower 相关 Binder IPC 类型，确保与 shower-server.jar 的类名保持一致
-keep class com.ai.assistance.shower.ShowerBinderContainer { *; }
-keep class com.ai.assistance.shower.IShowerService { *; }
-keep class com.ai.assistance.shower.IShowerVideoSink { *; }

# 保留自定义的 UserService 类及 AIDL 接口
-keep class com.ai.assistance.operit.core.tools.system.shizuku.OperitShizukuShellUserService { *; }
-keep class com.ai.assistance.operit.core.tools.system.shizuku.IOperitShizukuShellService { *; }
-keep interface com.ai.assistance.operit.core.tools.system.shizuku.IOperitShizukuShellService { *; }
-keep class com.ai.assistance.operit.core.tools.system.shizuku.IOperitShizukuShellCallback { *; }
-keep interface com.ai.assistance.operit.core.tools.system.shizuku.IOperitShizukuShellCallback { *; }
-keep class com.lyneon.cytoidinfoquerier.service.FileService { *; }
-keep class com.lyneon.cytoidinfoquerier.IFileService { *; }
-keep interface com.lyneon.cytoidinfoquerier.IFileService { *; }

# 保留 ServiceConnection 和 Binder 相关方法
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 保留 QuickJS 反射绑定对象
-keep class com.ai.assistance.operit.core.tools.javascript.JsEngine$JsToolCallInterface { *; }

# Rules to suppress R8 warnings about missing classes
# SVG Support
-dontwarn com.caverock.androidsvg.SVG
-dontwarn com.caverock.androidsvg.SVGParseException

# Java AWT classes (not available on Android)
-dontwarn java.awt.**
-dontwarn java.awt.color.**
-dontwarn java.awt.geom.**
-dontwarn java.awt.image.**

# Image processing libraries
-dontwarn javax.imageio.**
-dontwarn javax.xml.stream.**

# Saxon XML
-dontwarn net.sf.saxon.**

# Apache Batik
-dontwarn org.apache.batik.**

# OSGi Framework
-dontwarn org.osgi.framework.**

# XZ compression
-dontwarn org.tukaani.xz.**

# POI dependencies
-dontwarn org.apache.poi.xslf.draw.**
-dontwarn org.apache.poi.xslf.usermodel.**
-dontwarn org.apache.poi.util.**

# PDF Box dependencies
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**

# Apache commons compress
-dontwarn org.apache.commons.compress.archivers.sevenz.**

# xmlbeans
-dontwarn org.apache.xmlbeans.**

# GIF handling
-dontwarn pl.droidsonroids.gif.**

# Reactor BlockHound integration with Netty
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
-dontwarn io.netty.util.internal.Hidden$NettyBlockHoundIntegration

# ============================================================================
# Shaheen 3 release hardening (added 2026-11-04 for the first minified
# release; evidence and rationale recorded in OPERIT_BUILD_LOG.md,
# "Release prep (local)"). Each block is scoped to the code it protects;
# nothing here keeps the whole app.
# ============================================================================

# --- kotlinx-serialization (release audit risk #1) --------------------------
# 60 files under com.ai.assistance.operit carry @Serializable (data/model,
# data/preferences, data/api, core/tools/mcp, core/tools/packTool,
# integrations, chat persistence; ModelConfigData and CharacterCard among
# them). These are the documented kotlinx-serialization keeps: every rule is
# conditional on the @Serializable annotation, so no other class is retained
# by them.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep serializer() on companion objects of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep INSTANCE.serializer() of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Generated $$serializer classes of the app's own serializable types are
# instantiated reflectively by the serialization runtime.
-keep,includedescriptorclasses class com.ai.assistance.operit.**$$serializer { *; }

# Serialization reads annotation metadata (SerialName, polymorphic labels).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**

# --- ObjectBox Memory Vault (release audit risk #2) -------------------------
# Every @Entity lives in com.ai.assistance.operit.data.model (Memory,
# ChatEntity, MessageEntity, CharacterCard, DocumentChunk, PromptTag,
# TokenUsageRecordEntity, ...), and the ObjectBox plugin generates
# MyObjectBox + *_ property + *Cursor classes into that same package
# (data/db/ObjectBox.kt imports com.ai.assistance.operit.data.model
# .MyObjectBox). Entity and generated classes are bound together by the
# generated model registration, so the package stays unrenamed as one unit;
# it is also the heartland of the app's @Serializable DTOs.
-keep class com.ai.assistance.operit.data.model.** { *; }
-keep class io.objectbox.** { *; }

# --- JNI / native bridges (release audit risk #3) ---------------------------
# A class declaring native methods must keep its class and method names or
# the native side cannot resolve them after obfuscation. This standard rule
# is member-scoped: it retains nothing that declares no native methods. It
# also covers the terminal submodule's native bridges, which are not checked
# out in this working copy.
-keepclasseswithmembernames class * {
    native <methods>;
}

# App-side native bridges found by grep for `external fun`, named explicitly
# because native code also calls back into some of them by name:
#   com.k2fsa.sherpa.ncnn.SherpaNcnn (sherpa-ncnn JNI, bundled in app sources)
#   util.streamnative.NativeXmlSplitter / NativeMarkdownSplitter
#   util.ripgrep.NativeRipgrep
#   util.ToolPkgWasmRuntime (ToolPkg WASM runtime JNI)
#   core.tools.javascript.QuickJsNativeBridge (:quickjs module object whose
#   external funs are the QuickJS JNI entry points; the JsEngine
#   $JsToolCallInterface reflection bridge is already kept at the top of
#   this file)
-keep class com.k2fsa.sherpa.ncnn.** { *; }
-keep class com.ai.assistance.operit.util.streamnative.** { *; }
-keep class com.ai.assistance.operit.util.ripgrep.** { *; }
-keep class com.ai.assistance.operit.util.ToolPkgWasmRuntime { *; }
-keep class com.ai.assistance.operit.core.tools.javascript.QuickJsNativeBridge { *; }

# --- Reflection by name (release audit risk #4) -----------------------------
# ui/common/icons/MaterialIconNameResolver resolves icons purely by name:
#   Class.forName("androidx.compose.material.icons.filled.${name}Kt")
#       .getMethod("get${name}", Icons.Filled)
# so those library classes and their getter names must survive verbatim.
-keep class androidx.compose.material.icons.filled.** { *; }

# ui/common/composedsl/ToolPkgComposeDslScreen enumerates zero-arg getters
# and fields of these Compose classes and derives DSL tokens from member
# names at runtime (typographyGetterByToken, colorSchemeFieldByToken,
# alignment/arrangement/font-weight getter maps). Member names only; the
# classes themselves are statically referenced.
-keepclassmembernames class androidx.compose.material3.Typography {
    public *** get*();
}
-keepclassmembernames class androidx.compose.material3.ColorScheme {
    *** *;
}
-keepclassmembernames class androidx.compose.ui.Alignment** {
    public *** get*();
}
-keepclassmembernames class androidx.compose.foundation.layout.Arrangement** {
    public *** get*();
}
-keepclassmembernames class androidx.compose.ui.text.font.FontWeight$Companion {
    public *** get*();
}

# Recorded residual (no rule possible): core/tools/javascript's
# JsJavaBridgeDelegates.loadClass resolves ARBITRARY class names supplied by
# JS tool scripts via Class.forName; no finite keep can cover script-supplied
# names. The bridge's own interface is kept at the top of this file. Scripts
# hardcoding app class names are the known residual risk for the device
# smoke pass.

# --- Gemini Live voice (release audit risk #5): no rule needed --------------
# api/voice/GeminiLiveVoiceProvider, GeminiLiveToolDeclarations and
# RealtimeVoiceSession build and parse every socket frame MANUALLY with
# org.json.JSONObject (verified by grep: no kotlinx-serialization types in
# the Live path), so obfuscation cannot break its wire format. The two
# @Serializable files elsewhere in api/voice (OpenAIVoiceProvider,
# HttpTtsResponsePipelineStep) are covered by the serialization block above.

# --- Shaheen 3 release fix: optional-dependency references (R8 missing classes, run #136) ---
# pdfbox's optional JPEG 2000 decoder (not bundled; JPX image decoding only)
-dontwarn com.gemalto.jp2.**
# AutoValue-shaded JavaPoet references JDK annotation-processing APIs (compile-time only, absent on Android)
-dontwarn javax.lang.model.**
# Apache SSHD optional JMX + EdDSA references (absent on Android; those code paths are optional)
-dontwarn javax.management.**
-dontwarn net.i2p.crypto.eddsa.**
