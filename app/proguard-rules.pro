# Battery Alert App — ProGuard Rules

# Keep essential Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep SharedPreferences keys and constants used via reflection or cross-component
-keepclassmembers class com.batteryalert.app.MainActivity {
    public static final java.lang.String *;
}

# General optimization and obfuscation
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''
