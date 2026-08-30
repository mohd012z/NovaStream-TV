# PerfectTV Enhanced v2 Design

## Goal
Turn the functional prototype into a branded, responsive TV player with visible channel artwork, fast large-list browsing, clear player gestures, and lightweight 3D/glass styling.

## UI
Use a dark premium visual language with cyan/blue accents, gradients, shallow shadows and borders. The launcher icon, splash and in-app brand mark share the same play-symbol identity. Home surfaces Live Now, Continue Watching and Movies with artwork instead of text-only cards.

## Live library
Render `tvg-logo` using Coil, use LazyColumn/LazyRow, and index EPG by channel id. Channel rows show logo, name, group, current programme and progress. Category chips filter without rebuilding the whole screen.

## Player
Use Media3 without its default controller chrome. Single tap toggles custom controls. Swipe vertically on the left changes brightness; swipe on the right changes system media volume. Both show a percentage HUD. Live playback avoids VOD resume state and VOD/Series retain cache/resume.

## Performance
Parse large M3U/XMLTV data off the main thread, build an EPG index once per loaded EPG, lazy-render channel rows and image-load only visible artwork. Keep Live streams off the VOD disk cache and use bounded network timeouts/retry.

## Compatibility
Android minSdk 26, target/compile SDK 35, JDK 17, Media3 1.5.1. Allow cleartext streams because authorized M3U playlists may include HTTP entries. Preserve User-Agent/Referer headers including pipe-suffixed URL headers.
