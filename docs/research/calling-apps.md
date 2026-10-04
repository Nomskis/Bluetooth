# What calling apps do, and where Earshot stands

A checklist of what people expect from a calling app, from WhatsApp, Signal,
FaceTime, Telegram and Google Meet, and where Earshot is on each. Earshot is a
one-to-one calling app between people who know each other, so group features
are out of scope on purpose.

- ✅ done
- 🤔 maybe later (worth it only if it's asked for)
- ⛔ not for Earshot (and why)

## Ringing and answering

| Feature | Status | Notes |
| --- | --- | --- |
| Rings like a phone call, on the lock screen, full screen | ✅ | Android's incoming-call notification, ringtone, vibration, Do Not Disturb respected |
| Answer, decline, answer without video | ✅ | |
| Reply with a message instead of answering | ✅ | Three quick answers or your own words |
| Missed call notification with Call back and Message | ✅ | |
| Recent calls (incoming, outgoing, missed in red) | ✅ | Home screen; a tap calls back the same way |
| "Call ended" with how long you talked | ✅ | For a moment on the home screen |
| Busy when already in a call | ✅ | Call waiting (end this call and take the new one) is 🤔 |
| Both calling each other at once | ✅ | The phones agree on one call |
| Block someone | ✅ | Their calls don't ring and their messages are dropped; they aren't told. Unblock in Settings |
| Bluetooth headset and car buttons answer and hang up (Android Telecom) | 🤔 | Needs Android's ConnectionService; Earshot's Hi-Fi audio works around the call system on purpose |

## In a call

| Feature | Status | Notes |
| --- | --- | --- |
| Mute, camera on/off, switch camera, hang up | ✅ | One row that hides after a few seconds; a tap brings it back, and that tap can't hang up |
| Use the rest of the app during a call | ✅ | A bar at the top goes back to the call |
| Voice call to video call and back, in one tap | ✅ | No reconnecting |
| Loudspeaker or earpiece, screen off at your ear | ✅ | Like the phone app |
| Call timer, who's muted, weak connection | ✅ | |
| Move your own video, swap big and small | ✅ | |
| Picture-in-picture when you leave the app | ✅ | |
| Ongoing call notification with Mute and Hang up | ✅ | Android's call style, with the timer |
| Chat during the call | ✅ | Lands in the conversation too |
| Your own quick replies | ✅ | Settings; used in the call's chat and when declining |
| Survives switching Wi-Fi and mobile data | ✅ | |
| Use less data for calls | ✅ | Settings: video at most 300 kbps and 15 fps both ways, the voice untouched |
| Screen sharing | 🤔 | WhatsApp, Meet and Telegram have it |
| Background blur, filters, reactions | ⛔ | Costs CPU and battery the call quality needs |
| Group calls | ⛔ | One-to-one by design |
| Recording | ⛔ | |

## Messages

| Feature | Status | Notes |
| --- | --- | --- |
| A conversation per contact, kept on the phone | ✅ | |
| Messages wait for a phone that's offline | ✅ | On your own server, until their phone confirms them |
| Sent and delivered | ✅ | |
| Read ("Seen") | ✅ | Sent when the conversation is on screen or you reply from the notification |
| Reply or mark as read from the notification | ✅ | Marking as read tells them, like opening the chat |
| Unread counts and latest message on the home screen | ✅ | |
| Date headers | ✅ | |
| Copy or delete a message, clear a chat | ✅ | Long-press a message; Clear chat in the menu. Off this phone only |
| Voice messages, photos | 🤔 | Need storage on the server |
| Reactions, replies to a message, editing | 🤔 | |
| Typing indicator | ⛔ | More traffic on a slow link, for little |

## Contacts and setup

| Feature | Status | Notes |
| --- | --- | --- |
| Add someone with a link | ✅ | The link opens the app straight into the call |
| Rename a contact | ✅ | |
| No account, no phone number | ✅ | Your own server; see the README for why not phone numbers |
| Updates inside the app | ✅ | And Check for updates in Settings |
