# JAudioTagger ships desktop artwork helpers (java.awt / javax.imageio / javax.swing) that are never loaded on
# Android because TagOptionSingleton.isAndroid is set by the tag reader.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
