# libmpv registers its natives against these exact names and calls back into
# MPVLib's observer interfaces from its own threads. There is no reference R8 can
# follow from Kotlin to any of it, so neither the classes nor their members may be
# renamed or removed.
-keep class dev.jdtech.mpv.** { *; }

# kotlinx.serialization 1.6.3 ships consumer rules covering the Companion and
# serializer() lookup paths, so all that is left to state is that the annotated models
# and their generated serializers must survive shrinking. Keying the keeps on the
# annotation rather than on the package is what lets R8 still inline and shrink the
# non-model code in the same packages (TmdbClient, CatalogRails, StreamCatalog,
# StreamQuality); a package-wide `{ *; }` keep silently opted all of it out.
-keep,allowobfuscation @kotlinx.serialization.Serializable class com.stremioshell.host.tv.data.**
-keep,allowobfuscation,includedescriptorclasses class com.stremioshell.host.tv.data.**$$serializer { *; }
-keepclassmembers class com.stremioshell.host.tv.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# The diagnostics report names classes via javaClass.simpleName (NebulaDiagnostics.record,
# the player's failure records), and those reports are read off a TV screen by a person.
# keepnames still lets R8 drop unused classes entirely and shrink members - it only stops
# the survivors being renamed to single letters, which costs string data, not code.
-keepnames class com.stremioshell.host.** { }
