# Android CamSwap

[English](README.md) | [简体中文](README_zh.md)

Android CamSwap is an Xposed-based virtual camera module for Android. It intercepts camera preview and capture feeds, redirecting them to user-specified local videos, images, or live network streams.

Built with Kotlin and Jetpack Compose, CamSwap features cross-process IPC communication and full compatibility with modern Android versions (Android 8.0+).

## Features

- Camera API Support: Compatible with both Camera1 and Camera2 APIs.
- Video and Stream Replacement: Replace camera feeds with local video files (MP4) or live streams (RTSP, RTMP, HLS, DASH).
- Audio Replacement: Synchronize audio with the replaced video or play custom audio tracks.
- Real-Time Controls: Notification bar shortcuts for switching media, adjusting rotation (+90° / -90°), and toggling playback options.
- Auto Rotation: Automatically parses video orientation metadata and applies correct OpenGL ES rendering.
- Target App Filtering: Limit hook activation to specific applications.
- Modern Management UI: Clean Material Design 3 interface built with Jetpack Compose.

## Prerequisites

- Android 8.0 (API 26) or higher (Android 11+ recommended)
- Root access
- Xposed framework (LSPosed recommended)

## Quick Start

### 1. Installation and Activation
1. Download and install the latest APK release on your device (most modern devices use `arm64-v8a`).
2. Open LSPosed Manager and enable the **CamSwap** module.
3. In LSPosed scope settings, select the target applications that require camera redirection.
4. Restart the target applications.

### 2. Configuration
1. Open the **CamSwap** app and grant the necessary storage permissions.
2. In the management tab, import your video or media files.
3. In the settings tab, choose your default media and configure audio or playback options as needed.

### 3. Usage
Open any selected target application that uses the camera. The preview and capture feeds will be replaced with your selected media.

## Storage Path

Configuration and media files are located at:
```
/sdcard/DCIM/Camera1/
```
Configuration file: `cs_config.json`

## Disclaimer

This project is intended solely for security research, software testing, and educational purposes. Do not use this project for any illegal or unauthorized activities (including but not limited to biometric authentication bypass or identity fraud). Users are solely responsible for compliance with applicable laws and regulations.

## Credits & License

- Inspired by [android_virtual_cam](https://github.com/w2016561536/android_virtual_cam).
- Licensed under the [GPL-3.0 License](LICENSE).
