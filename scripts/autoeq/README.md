# AutoEq profile database

`build_autoeq_db.py` turns [AutoEq](https://github.com/jaakkopasanen/AutoEq)'s
`results/<source>/<rig>/<headphone>/<headphone> ParametricEQ.txt` files into
`data/src/main/assets/autoeq/autoeq_profiles.json`. That file ships inside the APK and
backs the headphone picker in Now Playing > Equalizer. It is about 3.4 MB raw and about
0.7 MB compressed in the APK, and covers roughly 6,000 headphones.

```json
{"schemaVersion": 1, "autoeqCommit": "<sha>", "profiles": [
  {"id": "sennheiser-hd-650", "name": "Sennheiser HD 650", "type": "over-ear", "source": "oratory1990",
   "preamp": -6.4, "filters": [{"type": "LSC", "fc": 105.0, "gain": 5.5, "q": 0.7}, ...]}
]}
```

`AutoEqDatabase.kt` reads this file. Bump `SCHEMA_VERSION` in both places if the shape
changes. `AutoEqDatabaseTest` parses the bundled file, so a broken refresh fails CI.

**Duplicates.** One profile per headphone. Names are matched ignoring case, spaces and
punctuation. The source is chosen by `SOURCE_PRIORITY` (oratory1990 > crinacle > Rtings >
Innerfidelity > others), and ties are broken by `RIG_PRIORITY` and then by path.

**Updating.** `.github/workflows/autoeq-db.yml` runs on the 1st of each month (and on manual
dispatch) and opens a PR when the data changed. The output is deterministic, so a run with no
upstream change opens no PR. The repo setting *Allow GitHub Actions to create and approve pull
requests* must be on.

## Running locally

```sh
git clone -c core.longpaths=true --depth 1 --filter=blob:none --no-checkout https://github.com/jaakkopasanen/AutoEq.git
git -C AutoEq sparse-checkout set --no-cone '/results/**/*ParametricEQ.txt' '/LICENSE'
git -C AutoEq checkout
python3 -I scripts/autoeq/build_autoeq_db.py AutoEq/results \
    data/src/main/assets/autoeq/autoeq_profiles.json --commit "$(git -C AutoEq rev-parse HEAD)"
```

On Windows, `core.longpaths=true` is required, or about 300 files silently fail to check out.

AutoEq is MIT licensed. Its notice ships as `assets/autoeq/LICENSE-AutoEq.txt` and must be
shown on the app's licenses screen.
