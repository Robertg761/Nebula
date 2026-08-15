# Error Prone annotations describe source-level Java modifiers. Their CLASS-retained metadata is
# harmless in an instrumentation APK, and Android has no javax.lang.model runtime implementation.
-dontwarn javax.lang.model.element.Modifier
