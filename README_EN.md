<p align="center">
  <img src="TMessagesProj/src/main/res/mipmap-xxxhdpi/ic_easygram_bloom_blue_dark_round.png" alt="Easygram icon" width="144" height="144">
</p>

<h1 align="center">Easygram</h1>

<p align="center">English · <a href="README.md">Русский</a></p>

Easygram is an unofficial Telegram client for Android. It is based on Telegram's open source code and adds new features to the familiar messaging experience. Its current focus is video message recording.

## Custom camera pipeline

On Android 9 and newer, Easygram records video messages through a separate Camera2 based camera pipeline. Recording is integrated into the chat interface, with its own camera, preview, audio, and video handling. Older Android versions use the existing recording path.

This provides:

- **More control over quality.** Video message settings let you choose the resolution, frame rate, video and audio bitrates, maximum duration, and image processing options.
- **Settings tailored to your device.** Available modes are checked for both the front and rear cameras. Unsupported options are marked as unavailable and cannot be saved.
- **A cleaner start to recording.** Recording begins after the camera is ready and the first preview frame appears. An optional delay helps keep the initial exposure jump out of the final video.
- **Convenient camera switching.** Easygram can remember the camera used for the last recording and start the next video message with it. When this option is off, recording starts with the front camera.
- **Light and focus controls.** Exposure, white balance, focus, stabilization, and other camera settings are available when supported by the device.

Find these options under **Settings → Video Messages**. Camera behavior and feature availability may vary by device.

## What's next

Easygram will continue to evolve, with more improvements planned for video message recording, image quality, and everyday usability. Updates will be introduced gradually.

## Building

The project uses Android SDK 36, Android NDK 27.2.12479018, and the Gradle Wrapper included in this repository. To build your own version, you need your own `api_id` and `api_hash`, Firebase configuration, and signing key. See the [Telegram API documentation](https://core.telegram.org/api/obtaining_api_id) for instructions on obtaining API credentials. Replace the sample values and configuration files with your own before publishing a build.

## Source and license

Easygram is an independent project and is not affiliated with the Telegram team. It is based on [Telegram for Android](https://github.com/DrKLO/Telegram). See [LICENSE](LICENSE) for the source code license.
