# Amplifree tone files

A tone file is a small JSON document that describes one tone: which model is in each slot and what
every setting is. It is Amplifree's own open format, written for sharing. It is not a copy of the amp's
stored tone data, and it does not come from or depend on any Line 6 file format.

```json
{
  "format": "amplifree-tone",
  "version": 1,
  "name": "My Lead",
  "blocks": [
    { "slot": "Amp", "model": "ModelName", "params": { "Drive": 0.63, "Bass": 0.5 } },
    { "slot": "FX1", "model": "OtherModel", "params": { "Mix": 0.3 } },
    { "slot": "GuitarProc", "model": "RoutingName", "params": { "FX1Enable": true } }
  ]
}
```

- `slot`, `model` and the parameter names are the names the amp itself reports. Amplifree reads them
  from the connected amp; the repository contains no model list.
- A parameter value is a number (knobs, mix levels), `true`/`false` (switches), a whole number
  (selectors) or a string (a choice, written by name).
- The routing block (`GuitarProc`) holds the on/off switches and mix levels for the effect slots.

## Importing

Importing applies a file to the **live tone only**, the same way the sliders do: models are swapped
with the amp's "load model" command, then each value is sent as a normal parameter change. Nothing
stored on the amp is rewritten, and **Reload from amp** brings back the tone you started from.

Because the amp stores whatever value it is given, Amplifree keeps every imported number to a sensible
range, skips names the amp does not know, ignores swaps to models the amp does not have, and refuses
files that are too large or malformed. The result is reported as "N settings applied, M skipped".

A file made on one amp model may not fit another: whatever does not match is skipped rather than guessed.
