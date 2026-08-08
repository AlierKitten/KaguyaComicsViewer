# Keep Room entities
-keep class com.kaguya.comicsviewer.data.local.entity.** { *; }

# smbj
-dontwarn com.hierynomus.**
-keep class com.hierynomus.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn jcifs.**
-keep class jcifs.** { *; }

# Coil
-dontwarn coil.**

# slf4j (transitive dependency from jcifs-ng / commons-compress)
-dontwarn org.slf4j.**
