# Competitor teardown: "get me back" on foot

Researched 2026-10-24 for the Gemma Hardware Hackathon pitch. Every claim below links to a source in the Sources section, cited as [n]. Anything I could not confirm is marked **unverified**. Do not say anything in the pitch that isn't backed here.

---

## Pitch ammo (five lines you can defend)

1. **Google Maps can't give walking directions offline.** Google's own help page says: "When you're offline, transit, bicycling, or walking directions are unavailable." Offline maps also expire and have to be refreshed [1]. Travis is built for walking and works in airplane mode.
2. **Every "find my car" feature saves a GPS pin, and GPS fails exactly where you park.** Google's help page says "GPS signals are not available indoors or underground" [3]. In cities, smartphone GPS is typically off by 7–13 m [17]. Under tree cover, sub-meter accuracy was not reached even with 10-minute fixes [19]. Travis remembers what the spot looks like, not only its coordinates.
3. **The AI camera assistants send your photo to a server, and none of them remembers your trip.** Gemini keeps chats for up to 72 hours even with Activity turned off. Chats that human reviewers looked at are kept for up to 3 years [10]. Apple's Visual Intelligence passes images to Google or ChatGPT, under their terms [11][12]. Lens works offline only for translation [9]. None of them can say "this is the junction *you* photographed this morning."
4. **Location data from fitness and trail apps has leaked in practice.** Strava's heatmap revealed the outlines of military bases in 2018 [13]. In 2024 it exposed where Biden, Macron, and Putin were, through their bodyguards' runs [14]. The FTC found a data broker pulling precise location from more than 300 apps, fitness apps among them [16]. Travis has no server to leak from: no account, no upload.
5. **Offline mode is a paywall and an account.** AllTrails puts map downloads and wrong-turn alerts behind Plus ($35.99/yr) [4]. Gaia needs an Outside account, and its full offline catalogue is Premium-only [6]. Komoot started charging new users for navigation [7]. Strava's offline routes need a subscription [8]. Travis's offline mode costs nothing, because the whole app is offline.

---

## Comparison table

Y = yes, N = no, P = partial, ? = unverified. Numbers in brackets are sources.

| Product | Offline maps | Works with zero signal | On-device AI for this task | Remembers landmarks by meaning | Photo check at junctions | Return reminders tied to places | No account | No upload of location + photos |
|---|---|---|---|---|---|---|---|---|
| **Travis** | N (no tiles) | Y | Y (Gemma 4 E2B, EmbeddingGemma) | Y | Y (against your own photo) | Y | Y | Y |
| Google Maps (offline, parking, Timeline) | Y, with expiry [1] | P: driving only, no offline walking directions [1] | N | N | P: Live View / Lens in Maps matches Street View, not your photos [2] | P: parking note + photo; the meter reminder is time-based [20] | ? | P: Timeline on device, optional encrypted backup [21][22] |
| Apple Maps + Parked Car | Y, select areas [23] | Y, incl. walking [23] | N | N | N | N (notes only, no photos per Apple) [24] | N (Apple ID, ? for Maps itself) | ? |
| AllTrails | P: Plus only [4] | P: Plus only [4] | N | N | N | N | ? | N: location linked to identity [4] |
| Gaia GPS | P: full catalogue Premium-only [6] | P | N | N | N | N | N: Outside account required [6] | N: location linked, tracking identifiers [6] |
| Komoot | P: paid region unlock [7] | P | N | N | N | N | ? | ? |
| Strava routes | P: subscribers [8] | P | N | N | N | N | ? | N: heatmap/Metro aggregates [13][15] |
| OsmAnd | Y: free tier capped (5 downloads on iOS per docs) [25] | Y | N | N | N | N | Y [26] | P: rotating install UUID sent with downloads [26] |
| maps.me | Y | Y | N | N | N | N | ? | P: 7 trackers in v15.9 (analytics/login SDKs) [27] |
| what3words | N: maps need data [28] | P: 3 words + compass work offline [28] | N (its AI chat is online-only) [28] | N | N | N | ? | ? |
| Find-my-car / backtrack apps | N | P: GPS pin + compass | N | N | N | N | ? | P: e.g. AdMob in one popular Android app [29] |
| Google Lens / Gemini Live | n/a | N: Lens offline = translate only [9] | N: cloud [10][30] | N: no trip memory | P: describes a scene, never compares it to yours | N | N | N [10] |
| ChatGPT vision | n/a | N | N: cloud | N | P: same as above | N | N | N |
| Apple Visual Intelligence | n/a | ? | P: Ask/Search go to ChatGPT/Google [11][12] | N | N | N | N | P [11] |

"Photo check at junctions" means comparing the live camera view with a photo *you* took earlier on the same trip. No competitor found does this.

---

## Teardowns

### Google Maps
- **What it does well:** The best map data anywhere. Offline driving navigation works inside a downloaded area [1].
- **Where it falls short:**
  - The official help page says outright that offline walking directions are unavailable [1]. That's the exact case Travis covers.
  - Offline areas expire and are only refreshed automatically over Wi-Fi [1]. Some countries can't be downloaded at all [1].
  - Saved parking is a GPS pin. Notes, photos, and time left on the meter can be attached, but the reminder fires at a time, not when you reach a place [20]. The 2017 coverage is old, so check the current app.
  - Lens in Maps / Live View works only where "the walking area must have good Street View coverage," and only in select countries [2]. It matches Google's Street View imagery, not your photos, and trails and garages are usually outside that coverage.
  - Credit where due: Timeline now lives on the device by default, with an optional encrypted cloud backup [21][22]. In March 2025 a bug wiped Timelines for some users, and anyone without a backup couldn't recover theirs [18]. Timeline is passive history, not "remember this spot for me."

### Apple Maps / Parked Car
- **What it does well:** Offline maps with walking turn-by-turn since iOS 17 [23]. The parked-car pin drops automatically [24].
- **Where it falls short:** The automatic pin only drops when the phone disconnects from the car's Bluetooth or CarPlay [24]. Apple's page mentions notes but not photos [24]. iPhone only, so it doesn't run on our target device. It has no landmark memory, no photo comparison, and no place-based reminder.

### AllTrails
- **What it does well:** Huge trail catalogue. Wrong-turn alerts are useful when you're on a mapped trail [4][5].
- **Where it falls short:** Offline map downloads and wrong-turn alerts are both Plus features ($35.99; Peak is $79.99) [4]. Wrong-turn alerts only work against a *preloaded* route, and loading a trail from its details page needs data [5]. They trigger at about 50 m off-route [5], which is coarser than a typical junction. The App Store privacy label lists location as "linked to you," with advertising data used for third-party ads [4]. Reviewers complain about aggressive upsell prompts [31] (aggregator summary, weak source).

### Gaia GPS
- **What it does well:** Serious backcountry layers, including NatGeo Trails Illustrated and private lands with Outside+ [6].
- **Where it falls short:** You must "Create an Outside account to access the app." The full offline catalogue is Premium. The privacy label lists location linked to identity, plus identifiers that "may be used to track you" across other companies' apps [6]. How much offline storage the free tier gets is **unverified** (sources conflict).

### Komoot
- **What it does well:** Excellent route planning. Offline navigation needs a region unlock, not a subscription [7][8].
- **Where it falls short:** In-app navigation, including offline maps, requires a purchase. Since 27 Feb 2025, new users need Premium ($59/yr) just to sync routes to a GPS device [7]. Bending Spoons bought Komoot in March 2025, and layoffs followed [32] (reports differ on the size; don't quote a percentage).

### Strava (routes)
- **What it does well:** Popularity-based routing, and the community knows its paths.
- **Where it falls short:** Offline routes are for subscribers [8]. The privacy record is the strongest point against it:
  - 2018: the global heatmap showed the outlines of US bases in Syria and Afghanistan [13].
  - Oct 2024: Le Monde traced the movements of Biden, Trump, Macron, and Putin through 26 Secret Service, 12 French, and 6 FSO bodyguards' public Strava profiles [14].
  - Strava says it doesn't sell personal information but does share aggregated, de-identified data, for example through Metro [15].

### OsmAnd
- **What it does well:** Open source, fully offline vector maps, and no account needed [26]. **Be generous here: it's the most honest privacy competitor.**
- **Where it falls short:** The free tier caps map downloads (iOS docs say 5, and map updates count toward that) [25]. It sends a rotating install UUID with map downloads, and on iOS usage data is on by default [26]. It's a map with GPS tracks: no AI, no landmark memory, no photo check.

### maps.me
- **What it does well:** Free offline maps. Hikers use it as a free workaround for AllTrails' offline paywall [33].
- **Where it falls short:** Exodus found 19 trackers in 2017, including ad SDKs, and 7 in v15.9 (2024) [27]. Exodus detects SDKs in the build; that's not proof data was sent. No AI, no landmark memory.

### what3words
- **What it does well:** The 3-word address and compass mode work "even when you're totally offline" [28].
- **Where it falls short:** Maps need a data connection, and its AI Chat doesn't work offline [28]. The 3 words are just a coordinate, so they carry the same GPS error as any pin. It doesn't remember what the place looks like.

### Dedicated find-my-car / backtrack apps
- **What they do well:** A single tap saves a pin, and a compass arrow points back to it.
- **Where they fall short:** They rely on GPS alone. The Back-Track listing itself warns that background GPS "can dramatically decrease battery life" [34]. The Bushnell BackTrack device works only outdoors [34]. A popular Android "Find My Parked Car" app includes Google AdMob and asks for precise location and internet permissions [29]. None of them understands landmarks.

### AI camera assistants (Google Lens, Gemini Live, ChatGPT, Apple Visual Intelligence)
- **What they do well:** Strong scene understanding; they can read signs and identify landmarks.
- **Where they fall short:**
  - **They need the network.** Lens offline is translation only [9]. A Gemini Live hands-on stalled when Wi-Fi dropped [30].
  - **They upload.** Gemini keeps chats for up to 72 hours even with Activity off. Reviewed chats, along with location info, are kept for up to 3 years [10]. Apple Visual Intelligence sends images to Google ("Subject to Google's terms of use and data privacy policies") or ChatGPT [11][12].
  - **They have no memory of your trip.** They can describe a junction but can't tell you it's the one you passed at 9 a.m.
  - **Uploading the photo is itself the privacy risk.** A 2025 study found o3 placed everyday photos within a mile of where they were taken 60% of the time [35].

---

## Failure modes in real conditions (shared by every GPS-pin product)
- **Garages, indoors, underground:** "GPS signals are not available indoors or underground." Some phones' battery saver also turns GPS off when the battery is low [3].
- **Urban canyons:** A smartphone urban study measured 7–13 m average horizontal error [17].
- **Forest:** Results varied widely across 11 receivers under canopy. The best dual-frequency phone had a DRMS of 4.56 m [19], and sub-meter accuracy was not reached even with 10-minute observations [36].
- **Battery:** A peer-reviewed comparison found GPS drained the battery much faster under weak signal (38% vs 13%) [37]. Don't quote a per-hour number; I found no rigorous one.
- **No signal on trails:** Most of the apps above make you buy offline mode in advance, and AllTrails needs data to load a trail into Navigate [5].

Pitch framing: Travis doesn't fix GPS. It adds a second, independent cue, what the place looks like, exactly where GPS is weakest.

---

## Where Travis is honestly weaker (prepare for these questions)
- **No map tiles and no turn-by-turn.** Travis can't plan a route or show where you are on a map. OsmAnd, Apple Maps, and AllTrails can. Answer: by design, Travis is a memory layer that complements maps.
- **Short routes, a few spots, one device.** It's built for a parking-lot-to-trailhead walk, not a 30 km backcountry route. It has only been tested on a Pixel 11 Pro, and a trip deleted on the phone is gone, with no backup.
- **Vision can be wrong.** At night, in snow, or where everything looks alike (garage level P2 vs P3), Gemma may say "not sure." That's honest, but it isn't a fix. The user always confirms.
- **"Google could ship this tomorrow."** They have the models (Gemma, Gemini Nano). Answer: nobody has shipped it, the incumbents' business pulls toward cloud and accounts, and Travis shows the whole loop running on the phone's NPU today.

---

## Sources
1. Google Maps Help, Download areas & navigate offline: https://support.google.com/maps/answer/6291838?hl=en
2. Google Maps Help, Live View / Lens in Maps: https://support.google.com/maps/answer/9332056?hl=en
3. Google Maps Help, Troubleshoot "GPS Signal Lost": https://support.google.com/maps/answer/12244322?hl=en
4. AllTrails, App Store listing (features, prices, privacy label): https://apps.apple.com/us/app/alltrails-hike-bike-run/id405075943
5. AllTrails Help, Wrong turn alerts: https://support.alltrails.com/hc/en-us/articles/37213407013908-Wrong-turn-alerts (page returned 403 to my fetch; details come from search-indexed text of this page. Re-check before quoting "50 m.")
6. Gaia GPS, App Store listing: https://apps.apple.com/us/app/gaia-gps-mobile-trail-maps/id1201979492
7. DC Rainmaker, Komoot's expanded paywalls (Mar 2025): https://dcrainmaker.com/2025/03/komoots-expanded-paywalls-trying-to-make-sense-of-it.html
8. Appleosophy, Strava and Komoot offline maps on Apple Watch (Jan 2026): https://appleosophy.com/2026/01/29/strava-and-komoot-bring-offline-maps-to-apple-watch/
9. 9to5Google, Google Lens offline translate (2021): https://9to5google.com/2021/01/25/google-lens-translate-offline/
10. Gemini Apps Privacy Hub: https://support.google.com/gemini/answer/13594961?hl=en
11. Apple Support, Use visual intelligence on iPhone: https://support.apple.com/en-me/guide/iphone/iph12eb1545e/ios
12. TechCrunch, Apple Visual Intelligence launch coverage: https://techcrunch.com/?p=2851247
13. CNBC, Strava heat map exposing military info (Jan 2018): https://www.cnbc.com/2018/01/28/global-heat-map-for-joggers-exposing-sensitive-us-military-information.html
14. heise, Bodyguards make heads of state's whereabouts public via Strava (Oct 2024): https://heise.de/-9997223 ; road.cc: https://cdn.road.cc/content/news/bodyguard-strava-use-putting-leaders-danger-311007
15. Strava privacy policy (archived 2021): https://webcf.waybackmachine.org/web/20211009195900/https://www.strava.com/privacy
16. EFF, FTC bars X-Mode from selling sensitive location data (Jan 2024): https://www.eff.org/deeplinks/2024/01/ftc-bars-x-mode-selling-sensitive-location-data
17. Smartphone GPS accuracy study in an urban environment, PLOS ONE 2019: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6638960/
18. GSMArena, Google inadvertently deleted some Maps Timelines (Mar 2025): https://www.gsmarena.com/google_has_inadvertently_deleted_some_peoples_maps_timelines-news-67095.php
19. Purfürst, smartphone GNSS under forest canopy, Sensors 2022: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8838512/
20. TechCrunch, Google Maps lets you record parking location, time left at the meter (2017): https://techcrunch.com/2017/03/20/google-maps-lets-you-record-your-parking-location-time-left-at-the-meter/
21. Google Maps Help, Timeline: https://support.google.com/maps/answer/6258979?hl=en
22. 9to5Google, Location History / Timeline moving on-device (Dec 2023): https://9to5google.com/2023/12/12/google-location-history-timeline-device/
23. Apple Support, Download maps to use offline on iPhone: https://support.apple.com/en-us/HT213829
24. Apple Support, Find your parked car with Maps: https://support.apple.com/en-us/101587
25. OsmAnd docs, iOS purchases: https://docs.osmand.net/docs/user/purchases/ios
26. Privacy Guides, Maps (OsmAnd notes): https://www.privacyguides.org/en/maps
27. Exodus Privacy, maps.me reports: https://reports.exodus-privacy.eu.org/en/reports/401 and https://reports.exodus-privacy.eu.org/en/reports/467298
28. what3words support, Does the app work without signal?: https://support.what3words.com/en/articles/1520066
29. Exodus Privacy, Find My Parked Car (com.ofirmiron.findmycarandroidwear): https://reports.exodus-privacy.eu.org/en/reports/5107
30. PhoneArena, Gemini Live hands-on: https://www.phonearena.com/news/gemini-live-google-first-look-at-natural-ai-conversations-on-android_id158314
31. marlvel.ai, AllTrails review summary (weak source): https://marlvel.ai/apps/com-alltrails-alltrails/reviews
32. Advnture, Komoot taken over by Bending Spoons: https://www.advnture.com/news/big-changes-may-be-coming-for-komoot-users-as-the-navigation-app-is-taken-over-by-firm-with-a-history-of-slashing-jobs
33. Peak District, AllTrails review: https://www.peakdistrict.org/alltrails-review/
34. Back-Track listing (iPhoneAddict): https://iphoneaddict.fr/apps/navigation/back-track.html ; NBC Bay Area, Bushnell BackTrack: https://www.nbcbayarea.com/local/gadget-friday-helps-you-find-your-car/1847397/
35. "Doxing via the Lens," arXiv 2504.19373: https://arxiv.org/abs/2504.19373v2
36. Whitehall Forest GNSS study, PMC: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10016660/
37. Texas A&M-Kingsville, GPS energy use in Android location apps: https://digitalcommons.tamusa.edu/computer_faculty/15
