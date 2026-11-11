# Keep Xposed entry points
-keep class com.branchcam.hook.** { *; }

# Keep hook class names (LSPosed reads xposed_init by class name)
-keepnames class * implements de.robv.android.xposed.IXposedHookLoadPackage

# Material / AppCompat
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ViewBinding
-keep class com.branchcam.xposed.databinding.** { *; }
