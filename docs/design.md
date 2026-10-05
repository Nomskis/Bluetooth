# Earshot's design

How the app looks and why: what the research said, and the rules the screens follow.

## What the research said

- **Google's Material 3 Expressive studies** (46 studies, 18,000 people): people found
  the key action up to four times faster when it was bigger, higher in contrast and grouped
  in a container, and people over 45 did as well as younger ones. The same research warns
  that breaking familiar patterns (lists, labels) cost more than any expressiveness gained:
  "no amount of expressive design will beat basic functionality."
  ([Google Design](https://design.google/library/expressive-material-design-google-research))
- **Google's own Phone app** (2025): favourites and recent calls on one Home screen, each
  in a rounded container; in a call, larger oval buttons that change shape when pressed and
  a much larger pill-shaped end-call button; much larger caller names and photos when it
  rings. ([9to5Google](https://9to5google.com/2025/08/21/google-phone-material-3-expressive-redesign/),
  [Android Authority](https://www.androidauthority.com/phone-by-google-material-3-expressive-teardown-3562641/))
- **WhatsApp and Signal**: chats and calls as separate tabs at the bottom; the call's
  controls in a floating bar; a minimise button instead of back, so leaving the screen
  plainly doesn't end the call. ([Neowin](https://www.neowin.net/news/tags/whatsapp_calling/),
  [Signal](https://signal.org/blog/call-links/))
- **Google Messages** (2025): the conversation on its own rounded surface under the app
  bar, solid colours instead of patterned wallpaper.
  ([9to5Google](https://9to5google.com/2025/06/02/google-messages-material-3-expressive-chat/))
- **Android's guidance on colour**: dynamic colour (from the wallpaper) by default on
  Android 12 and later, a seed-based palette elsewhere, and colour roles rather than fixed
  hex values in components.
- **Hidden gestures**: what's only behind a gesture mostly goes undiscovered, so every
  long-press has a visible way too (Nielsen Norman Group).
- **What makes an app look AI-made**: purple and cyan gradients, glass cards, glowing
  halos behind content, the same big corner radius on everything, cards inside cards, icon
  tiles above headings, gradient text, pulsing dots that mean nothing, bouncy dialogs.
  ([Impeccable](https://www.impeccable.style/slop))

## The rules

**Structure**
- Two tabs at the bottom, as in WhatsApp and Signal: **Chats** (the people you talk to,
  with their latest message or call) and **Calls** (the history). Settings sits in the top
  bar. One button for something new: **Invite**, which offers a video or voice call link,
  or a room code.
- Notices (an update, a setup step, a cut-off call) only appear when they need you, at the
  top of Chats.
- A person's options (rename, block, remove) are in their conversation's menu, and on a
  long press of their row.

**Colour**
- Your wallpaper's colours on Android 12 and later; Earshot's green elsewhere. Light or
  dark as the phone is set.
- Calls are always dark, like the phone app's: they sit next to video, and a bright screen
  at night is unkind.
- Red only for ending or declining a call and for missed calls; green only for answering.
  No gradients, glows or glass.

**Shape and size**
- Lists are grouped in one rounded container whose rows are separated by small gaps
  (large outer corners, small inner ones), the way Android 16's own apps group them. No
  cards inside cards.
- Call buttons are 64 dp and change from a circle to a rounded square when they're on.
  Hang up is a wider pill, so it can't be mistaken for anything else. Everything you tap
  is at least 48 dp.
- People are a coloured circle with their initial, one colour per person, the same
  everywhere.

**Type**
- The phone's own font, so Earshot looks like it belongs on the phone. Names are large
  where they matter (a ringing call, a call screen); everything else is the normal scale.
- Short words: a label or a few words, never a paragraph.

**Motion**
- Springs for things that respond to your finger (a button changing shape), quick plain
  fades for things appearing. Nothing bounces, nothing pulses except a phone that's really
  ringing. Material's standard motion, not its springier expressive set.

## Where it lives

- `ui/theme/Theme.kt`: the colours (wallpaper or Earshot's green, light and dark), the
  always-dark call theme, the two call colours, and each person's colour.
- `ui/Components.kt`: the pieces every screen shares: the avatar, grouped rows, notices,
  the call buttons and the hang-up and answer buttons.
- Every screen builds from those; a new screen should too, rather than picking its own
  colours or corners.
