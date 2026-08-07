# Keep Room entities
-keep class com.kaguya.comicsviewer.data.local.entity.** { *; }

# smbj
-dontwarn com.hierynomus.**
-keep class com.hierynomus.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.apache.sshd.**
-dontwarn jcifs.**

# Coil
-dontwarn coil.**
