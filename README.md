# Disable distractions
- Instagram: suppress anything that's not chat &amp; friends' posts
- Google Maps: suppress the "Latest in &lt;your area&gt;" feature

## Motivation
I want to use Instagram to see friends' posts & stories, and chats. I
don't want to spend time watching strangers' Reels and posts.

Similarly, I never have use for the "Latest in &lt;your area&gt;" UI sheet
and always manually dismiss it. With this service, there's no need for
manual action anymore.

## What it does
When the `Reels` or `Explore and search` icons in the app's home view are
tapped, a fake "back" swipe will be performed to go back to the home
view. When scrolling the main feed and reaching `Suggested Posts` an
obscuring view will hide the rest of the feed below that header.

## How it works
This "app" registers an [Android Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility/service) that watches
actions taken in the relevant apps and performs a suitable gesture
depending on what it sees. There's no user interface or activity for
this app itself, it's purely a background process.

After installing, you'll have to enable this using: `Settings` ->
`Accessibility` -> tap `Disable Distractions` -> enable the main
toggle, and leave the shortcut-related disabled.  Note that
accessibility services have a tremendous level of access, so enabling
per above will show a scary consent screen.

## How to install
This isn't (yet?) available in Google's Play app store.

Either build using Android Studio (or gradle) from source, or download
the latest [release](https://github.com/fischman/DisableDistractions/releases) APK and install via adb or your side-loading
mechanism of choice.


## Note to self: How to build a new release
In Android Studio:
- Build -> Generate Signed Bundle/APK
- Tap next until can select build flavor and select only `release`
- APK is generated at `./app/release/app-release.apk`
- Create GitHub release with: `command gh release create v0.<N> --notes "<NOTES>" ./app/release/app-release.apk`
