# R8 rules of the release build, beyond those the libraries bring.
#
# Room, WorkManager, DataStore, Media3, Navigation, Lifecycle and the coroutines ship their own
# consumer rules: Room keeps the generated database implementation it loads by name, WorkManager
# keeps the names and constructors of the workers it stores and creates by reflection. The app
# itself uses no reflection.

# sherpa-onnx (speech recognition) is a downloaded AAR without consumer rules. Its native library
# reads the Kotlin config classes' fields by name through JNI and calls back into its classes, so
# none of them may be renamed or removed.
-keep class com.k2fsa.sherpa.onnx.** { *; }
