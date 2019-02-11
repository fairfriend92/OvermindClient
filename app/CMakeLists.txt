# Sets the minimum version of CMake required to build the native
# library. You should either keep the default value or only pass a
# value of 3.4.0 or lower.

cmake_minimum_required(VERSION 3.4.1)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds it for you.
# Gradle automatically packages shared libraries with your APK.

add_library( overmind
             SHARED
             src/main/jni/native_method.cpp
             src/main/jni/common.cpp
             src/main/jni/populations_methods.cpp)
include_directories( src/main/jni/include/ )
include_directories( src/main/assets/ )

add_library( GLES_mali
             SHARED
             IMPORTED )
set_target_properties( GLES_mali
             PROPERTIES IMPORTED_LOCATION
             ${PROJECT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libGLES_mali.so )

add_library( foo
             SHARED
             IMPORTED )
set_target_properties( foo
             PROPERTIES IMPORTED_LOCATION
             ${PROJECT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libfoo.so )

# Searches for a specified prebuilt library and stores the path as a
# variable. Because system libraries are included in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
              log-lib

              # Specifies the name of the NDK library that
              # you want CMake to locate.
              log)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in the
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries (overmind GLES_mali foo ${log-lib} ${dl-lib})

