# Contributing to Traffic

Traffic is an ATAK plugin, free software under the
[GNU Affero General Public License v3.0 or later](LICENSE), with an
[additional permission](LICENSE-EXCEPTION.md) covering the TAK SDK.

Contributions are welcome — bug reports, field evidence from real incidents,
and code.

## Before you write code

Open an issue first for anything larger than a bug fix. These plugins run on
phones at live incidents; a change that is right for one agency can break
another's fleet. Describing the problem before the patch saves the round trip.

The most valuable contribution is often not code: a precise field report — which
phone, which ATAK version, what you did, what happened — is worth more than a
speculative fix.

## Ground rules for code

- **Prefer the stable `gov.tak.api.*` classes** over `com.atakmap.android.*`
  internals wherever an equivalent exists. ATAK obfuscates its internals and the
  mapping changes between releases, so this decides whether the plugin survives
  an ATAK upgrade rather than failing in a way that looks like something else.
- **Dialogs and toasts use the MapView context, never the plugin context.** The
  plugin context throws `BadTokenException` and takes ATAK down with it.
- **No `Spinner`.** Its dropdown is a dialog built from the inflating context and
  hits that same crash. Use a button showing the current value that opens an
  `AlertDialog` with `setSingleChoiceItems`.
- **Distances follow ATAK's own unit preference** (`rab_rng_units_pref`,
  formatted through `SpanUtilities`), never a hardcoded unit.
- **Work that must outlive a tap does not live in a `Tool`.** ATAK ends the
  active tool whenever another starts, a pane opens, or Back is pressed. A
  recording, a download or a listener belongs in a component that lasts for the
  plugin's life.
- **Test the release build, not just debug.** `assembleCivRelease` runs minify
  and proguard; lambdas and reflection break there and nowhere else.
- **Nothing region-specific without saying so.** A default that only makes sense
  in one state is a bug the moment someone runs it elsewhere.

## License headers on new files

Every new source file gets an SPDX identifier:

```java
// SPDX-License-Identifier: AGPL-3.0-or-later
// Traffic — an ATAK plugin
// Copyright (C) 2026 Andreas Johansson (TAKWERX)
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU Affero General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option) any
// later version, with the additional permission in LICENSE-EXCEPTION.md.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
```

Use the `#` comment form for Python and shell. Do **not** add this header to
files listed under Provenance in [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md) —
those came from the TAK SDK's plugin template and are not ours to relicense.

## Vendoring third-party code

Must be compatible with AGPL-3.0-or-later. MIT, BSD, ISC and Apache-2.0 are
fine; GPLv2-**only** is not. Pin to a release tag and commit SHA, never a moving
branch, and name the license in the pull request.

## Data the plugin downloads or ships

Anything the plugin serves to users has to be something we are allowed to serve.
Agency-published records with a citable source and a date are ideal. Check the
terms on aggregator databases — several forbid redistribution even where the
underlying facts are public. Nothing marked CUI, FOUO or law-enforcement
sensitive, ever.

## Licensing of contributions

By submitting a contribution you agree to the terms in [CLA.md](CLA.md).

In short: you keep the copyright in your work, and you grant TAKWERX a license
broad enough to ship it as part of Traffic. This is what lets the project be
enforced as a whole — the AGPL's promise that it stays open is only meaningful if
there is a single party with standing to enforce it.

Sign by adding a `Signed-off-by:` line to your commits:

```bash
git commit -s -m "your message"
```

That line certifies you wrote the contribution, or otherwise have the right to
submit it under the AGPL, and that you accept [CLA.md](CLA.md).

**A bug report, a reproduction or a field correction needs no agreement at all,**
and is often the more useful contribution anyway.

## Reporting a security issue

Do not open a public issue. Use GitHub's private vulnerability reporting on this
repository — **Security → Report a vulnerability** — and give a reasonable window
for a fix before disclosure.

## Where this is built

Traffic is developed in
[takwerx/atak-plugins](https://github.com/takwerx/atak-plugins) alongside the
other TAKWERX plugins, and published here by subtree push. Pull requests against
this repository are the right place to send changes; they are merged back
upstream.
