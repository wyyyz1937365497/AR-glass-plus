cmake_minimum_required(VERSION 3.22.1)
project("ar_glass_uinput")

# uinput_jni.cpp: JNI bindings for /dev/uinput (virtual mouse) — necessary
# because Android 16 removed the generic Os.ioctl variant from the Kotlin API.
# Derived from pgratz1/AR-Touchpad (Apache-2.0), modified for AR-glass-plus.
add_library(ar_glass_uinput SHARED uinput_jni.cpp)
target_link_libraries(ar_glass_uinput android log)
