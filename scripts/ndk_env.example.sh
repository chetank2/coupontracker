#!/usr/bin/env bash
#
# NDK Environment Configuration
#
# Copy this file to scripts/ndk_env.sh and edit paths
#

# Android NDK path
# Find your NDK version in Android Studio → SDK Manager → SDK Tools → NDK
export ANDROID_NDK=$HOME/Library/Android/sdk/ndk/27.0.12077973

# Android SDK path
export ANDROID_HOME=$HOME/Library/Android/sdk

# Optional: Set Java home if needed
# export JAVA_HOME=$(/usr/libexec/java_home -v 17)

echo "NDK environment configured:"
echo "  ANDROID_NDK: $ANDROID_NDK"
echo "  ANDROID_HOME: $ANDROID_HOME"

