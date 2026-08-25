# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

HeliBoard is a privacy-conscious, offline Android keyboard (IME), forked from AOSP Keyboard / OpenBoard. It's a large, old codebase: much of it is original AOSP code with ancient comments/TODOs, mixed Java/Kotlin, plus a native (C++/JNI) dictionary engine.

## Build, lint, test

Gradle wrapper only (`./gradlew`, already executable):
- Build debug APK: `./gradlew assembleDebug`
- Build release APK: `./gradlew assembleRelease`
- Run JVM unit tests (Robolectric): `./gradlew testRunTestsUnitTest` — this is the `runTests` build variant, which is a debug-like variant with tests known to fail on CI excluded. This is what CI runs.
- Run a single test class: `./gradlew testRunTestsUnitTest --tests "helium314.keyboard.latin.SomeClassTest"`
- Lint (abortOnError is on, so lint failures break the build): `./gradlew lint`
- Native/JNI tests: `app/src/main/jni/run-tests.sh` (separate from the Gradle/JVM test suite, exercises the dictionary C++ code)

Test sources live in `app/src/test/java/helium314/keyboard/latin/`.

Build variants of note (see `app/build.gradle.kts`): `debug` (minified, for GitHub's 25 MB zip limit), `debugNoMinify` (fast IDE builds, debuggable), `runTests` (CI test target, unminified), `nouserlib`/`release` (no user-provided glide library).

There is no separate lint/format CLI beyond Android Lint + standard Kotlin/Java compilation — don't invent npm/pip-style commands.

## Architecture

The app has three layers: a **native C++ engine** (`app/src/main/jni/`) for dictionary lookup and gesture (glide) typing, a **legacy AOSP JNI bridge** (`com.android.inputmethod.*` package — kept minimal, mostly untouched from upstream), and the **application layer** (`helium314.keyboard.*` package — everything HeliBoard adds/modifies).

Key `helium314.keyboard` subpackages:
- `keyboard/` — keyboard view, key/layout rendering, touch tracking
- `latin/` — the IME service itself, input logic, suggestions, dictionaries
- `settings/` — Compose-based settings UI and persisted settings values
- `event/`, `compat/`, `accessibility/`, `dictionarypack/` — supporting infrastructure

### Main data/event flow
Touch and swipe input: `PointerTracker` → `KeyboardActionListenerImpl` → `LatinIME` (the `InputMethodService`, receives all IME lifecycle/editor events) → `InputLogic` (`latin/inputlogic/InputLogic.java`), which owns the actual text-editing decisions.

Suggestions pipeline (creation → display): `DictionaryFacilitatorImpl` → `Suggest` → `InputLogic` → `SuggestionStripView` (native dictionary lookup happens between `DictionaryFacilitatorImpl` and `Suggest`, via the JNI bridge to `app/src/main/jni`).

Communication with the target app's text field goes through `RichInputConnection` (both reading current text and issuing edits).

Settings: values are read from `SettingsValues` (`latin/settings/SettingsValues.java`), general settings logic lives in `Settings` (`latin/settings/Settings.kt`), and default values are centralized in `Defaults.kt` — check there before hardcoding a default anywhere else.

### Layouts
Keyboard layouts are JSON-ish text files under `app/src/main/assets/layouts/` (main, symbols, number pad, functional row, etc.), parsed by `KeyboardParser` (`keyboard/internal/keyboard_parser/KeyboardParser.kt`) into `TextKeyData` (`.../floris/TextKeyData.kt`). Per-language letter popups/variations that aren't layout-dependent live in `app/src/main/assets/locale_key_texts/`. See `layouts.md` at the repo root for the full layout file format and how to add new layouts/languages — read it before touching layout parsing or adding a language.

## Contribution norms (this repo is unusually strict about these)

- **No LLM-generated PRs, issues, or commit/PR descriptions.** This repo's `AI_USAGE.md` and `CONTRIBUTING.md` explicitly prohibit AI-generated contributions and communication; AI assistance is only acceptable if the contributor discloses it and can explain the code themselves. If you (Claude) are asked to open a PR, write an issue, or draft contributor-facing text for the *upstream* HeliBoard project, flag this restriction rather than doing it silently.
- **Keep changes minimal and localized.** Prefer reusing existing in-place mechanisms over introducing new ones; avoid spreading one change across many unrelated files.
- **Be careful in `InputLogic`, `Suggest`, `RichInputConnection`** — these are called extremely often, hard to test (behavior depends on the host app and OS version), and easy to break in non-obvious ways.
- **New user-facing features/behavior changes should be optional** (a setting), not forced on everyone.
- **Performance matters**: some code paths run on every keystroke/touch event; avoid adding noticeable overhead, keep older/low-end devices in mind.
- Do not add dictionaries (point contributors to the separate `aosp-dictionaries` repo) or translations (handled via Weblate, not PRs) in this repo.

## Note

`GEMINI.md` in this repo is a symlink to this file.

There's a `~/.gemini/settings.json` on this machine (Gemini CLI config) — if you want anything from it imported into Claude Code config, run `/import`.
# Contexto do fork

Fork de HeliBoard com gestos estilo Fleksy e melhorias de sugestão para PT-BR.
Branch de trabalho: `feature/fleksy-gestures`. Upstream: HeliBoard original.

## Ambiente

- Desenvolvimento em Arch Linux, deploy via ADB em um Galaxy A54.
- Ciclo padrão: build com Gradle, instalar por ADB, testar digitando no aparelho.
- Não há emulador no fluxo. Mudança de UI/gesto só se valida no aparelho físico.

## Áreas do código que este fork altera

- `PointerTracker.java` — gestos direcionais nas teclas de letra.
- `KeyDetector.java` — zonas de toque dinâmicas.
- `Suggest.kt` — reranking de candidatos.

Antes de mexer em qualquer um desses, leia o arquivo inteiro. São pontos de
alto acoplamento e mudanças pontuais costumam quebrar comportamento distante.

## Mapa de gestos implementado

Em teclas de letra:
- esquerda: apaga a palavra anterior
- direita: espaço
- cima/baixo: circula entre as sugestões sem commitar
- cima após espaço: reabre a última palavra

Detalhes que já custaram tempo e não devem ser revertidos sem motivo:
- Limiar de gesto em 28dp. Valor calibrado na mão; mudar afeta taxa de
  falso positivo em digitação normal.
- A circulação de sugestões usa a ordem **visual** da strip, não a ordem
  interna da lista.
- `mHasPinnedCycledWord` existe para a palavra circulada sobreviver a
  atualizações de sugestão. Remover reintroduz o bug de perder a seleção.
- `KeyCode.UNDO` foi substituído por `revertLastAutocorrect()` próprio.
- Deadzone do cursor na barra de espaço em 44dp.
- Em `ACTION_UP` a direção do swipe é reavaliada, para não perder gesto curto.

## Zonas de toque dinâmicas

`DYNAMIC_ZONE_MAX_SHIFT_RATIO = 0.22`, calibrado empiricamente. Vale também
para predição da primeira letra. Alterar esse valor muda a sensação de
digitação inteira — tratar como parâmetro sensível, não como constante livre.

## Em aberto

1. Reranking neural para melhorar qualidade dos candidatos em português.
   Prioridade. Hoje o 4º candidato frequentemente vem lixo.
2. Dwell gate para conviver com swipe typing. Baixa prioridade.
3. Possível ajuste do limiar de 28dp.
4. `fix_skip_emoji.py` foi gerado mas **não** aplicado. Deliberado. Não aplicar
   sem pedido explícito.

## Convenções para o agente

- Não commitar nem fazer push sem pedido explícito.
- Não rodar `adb install` sem avisar — o aparelho pode estar em uso.
- Mudanças de gesto e de zona de toque exigem teste manual no aparelho.
  Se não for possível testar, dizer isso em vez de assumir que funcionou.
- Ao alterar constante calibrada, mencionar o valor antigo e o novo.
