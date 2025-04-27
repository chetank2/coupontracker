#!/bin/bash

# Add Android SDK tools to PATH
echo 'export ANDROID_HOME=~/Library/Android/sdk' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/tools' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/tools/bin' >> ~/.zshrc

# Make the changes take effect
source ~/.zshrc

echo "Android SDK tools have been added to your PATH."
echo "You can now use 'adb' and other Android tools directly from the terminal." 