# Components are kept automatically because the manifest references them, but
# the AccessibilityService / NotificationListenerService base classes must keep
# the members the system calls reflectively.
-keep class com.staad.island.IslandService { *; }
-keep class com.staad.island.IslandNotificationListener { *; }
-keep class com.staad.island.MainActivity { *; }

# Keep line numbers so a stack trace from a release build is still usable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
